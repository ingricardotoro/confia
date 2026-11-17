package com.confia.support;

import com.confia.shared.security.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Base of every {@code *IT.java} class that needs rows COMMITTED and visible outside their own
 * transaction (design.md, decision 9). No {@code @Transactional}: nothing rolls back automatically.
 *
 * <p><b>Why {@link PostgresIntegrationTest#withInstitutionContext} cannot seed rows for a subclass
 * of this class (design.md decision 9, point 1).</b> That helper's own Javadoc already documents
 * it: {@code set_config(..., true)} is local to the current transaction, and without an outer
 * {@code @Transactional} test transaction to hold it, the setting disappears before the very next
 * statement runs, which reads back as the row-level-security policy denying by default rather than
 * as a real bug — a false green of exactly the family this whole change exists to close. The
 * correct way for a subclass of this class to seed a committed row is through {@link
 * #transactionRunner()}: the real, production {@link TransactionRunner}, which opens one genuine
 * transaction, fixes the security context as its first statement, runs the seeding use case, and
 * commits — the same path a real caller would take. A test that needs to bypass the component on
 * purpose (the {@code SUPERUSER} manipulation scenario, design.md decision 10) uses a raw
 * connection from {@link SharedPostgresContainer#connectionAs(String)} instead.
 *
 * <p><b>Truncation is selective and catalog-derived, never a hand-written table list</b> (design.md
 * decision 9, point 2): every base table of {@code public} that does <em>not</em> carry a {@code
 * BEFORE TRUNCATE} trigger is truncated, as {@code confia_owner} — {@code confia_admin_app} has no
 * {@code TRUNCATE} privilege on anything. A future append-only table (this change's own
 * {@code shared_audit_log}, PR B2a onward) is excluded automatically the moment its migration adds
 * that trigger, with nothing here to remember to update. With no append-only table shipped yet in
 * this PR, the derived set coincides exactly with the part A set ({@code organization_institution});
 * the assertion that the set actually <em>excludes</em> the audit tables is
 * {@code CommittingBaseContractIT}, delivered in PR B2a once those tables exist (task 2.4).
 */
public abstract class CommittingPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionRunner transactionRunner;

    /** The real, production {@link TransactionRunner}, built on this test's own connection pool. */
    protected TransactionRunner transactionRunner() {
        if (transactionRunner == null) {
            transactionRunner = new TransactionRunner(transactionManager, dataSource);
        }
        return transactionRunner;
    }

    /**
     * The pooled {@link DataSource} backing {@link #transactionRunner()}, for a subclass that needs
     * its own, independent {@link TransactionRunner} instances — for example one per thread in a
     * genuine concurrency scenario ({@link com.confia.shared.security.TransactionRunnerRetryIT},
     * design.md §7.2: "dos hilos, cada uno con su propio TransactionRunner y su propia conexión").
     */
    protected DataSource dataSource() {
        return dataSource;
    }

    /** See {@link #dataSource()}. */
    protected PlatformTransactionManager transactionManager() {
        return transactionManager;
    }

    @AfterEach
    void truncateCommittedBusinessTables() throws SQLException {
        List<String> tables = tablesWithoutABeforeTruncateTrigger();
        if (tables.isEmpty()) {
            return;
        }
        // Identifiers cannot be bound as query parameters in any SQL dialect (CLAUDE.md rule 12
        // targets untrusted, attacker-reachable input; these names come from pg_class, the server's
        // own catalog, never from a caller). Double-quoted so a mixed-case or reserved-word table
        // name — none exist today — would still truncate correctly instead of silently failing.
        List<GuardedTable> guarded = guardedTablesReferencingTruncatedOnes();
        String fullList = Stream.concat(tables.stream(), guarded.stream().map(GuardedTable::name).distinct())
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(", "));
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_owner");
                var statement = connection.createStatement()) {
            // One owner transaction: the TRUNCATE trigger of each guarded table (an append-only table that has a
            // foreign key to a truncated table, for example the consent events of the guardians) is disabled, the
            // tables are truncated together, and the trigger is enabled again before the commit. A failure rolls
            // everything back, trigger state included. shared_audit_log and shared_audit_chain_head have no foreign
            // key, so they never enter this set and stay out of the cleanup.
            connection.setAutoCommit(false);
            try {
                for (GuardedTable table : guarded) {
                    statement.execute("alter table \"" + table.name() + "\" disable trigger \"" + table.trigger()
                            + "\"");
                }
                statement.execute("truncate table " + fullList);
                for (GuardedTable table : guarded) {
                    statement.execute("alter table \"" + table.name() + "\" enable trigger \"" + table.trigger()
                            + "\"");
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** A table with a TRUNCATE trigger and the name of that trigger. */
    record GuardedTable(String name, String trigger) {
    }

    /**
     * The tables that carry a BEFORE TRUNCATE trigger and a foreign key to a table of {@link
     * #tablesWithoutABeforeTruncateTrigger()}: a truncate of the referenced table cannot succeed without them.
     * Derived from the catalog, like the main set.
     */
    List<GuardedTable> guardedTablesReferencingTruncatedOnes() throws SQLException {
        // Every table with a TRUNCATE trigger, its trigger and the tables its foreign keys point at. A guarded table
        // enters the set when it points at a truncated table or, transitively, at a guarded table that does (the
        // entries of the ledger point at its transactions, which point at nothing truncated): a TRUNCATE of a
        // referenced table fails unless every referencing table is truncated in the same statement.
        java.util.Map<String, String> triggerOf = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.Set<String>> parentsOf = new java.util.HashMap<>();
        java.util.Set<String> truncated = new java.util.HashSet<>(tablesWithoutABeforeTruncateTrigger());
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_owner");
                PreparedStatement statement = connection.prepareStatement("""
                        select c.relname, t.tgname, r.relname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                          join pg_trigger t on t.tgrelid = c.oid and not t.tgisinternal and (t.tgtype & 32) <> 0
                          left join pg_constraint k on k.conrelid = c.oid and k.contype = 'f'
                          left join pg_class r on r.oid = k.confrelid and r.relnamespace = n.oid
                         where n.nspname = 'public' and c.relkind = 'r'
                        """);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                triggerOf.putIfAbsent(resultSet.getString(1), resultSet.getString(2));
                if (resultSet.getString(3) != null) {
                    parentsOf.computeIfAbsent(resultSet.getString(1), k -> new java.util.HashSet<>())
                            .add(resultSet.getString(3));
                }
            }
        }
        List<GuardedTable> guarded = new ArrayList<>();
        java.util.Set<String> chosen = new java.util.HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var entry : triggerOf.entrySet()) {
                String table = entry.getKey();
                if (chosen.contains(table)) {
                    continue;
                }
                for (String parent : parentsOf.getOrDefault(table, java.util.Set.of())) {
                    if (truncated.contains(parent) || chosen.contains(parent)) {
                        chosen.add(table);
                        guarded.add(new GuardedTable(table, entry.getValue()));
                        grew = true;
                        break;
                    }
                }
            }
        }
        return guarded;
    }

    /**
     * Package-private, not {@code private}: {@code CommittingBaseContractIT} (task 2.4, design.md
     * decision 9, piece 3) asserts directly on this derivation's own output, rather than duplicating
     * the catalog query, so the contract test exercises the real implementation.
     */
    List<String> tablesWithoutABeforeTruncateTrigger() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_owner");
                PreparedStatement statement = connection.prepareStatement("""
                        select c.relname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace
                         where n.nspname = 'public' and c.relkind = 'r'
                           and c.relname <> 'flyway_schema_history'
                           and not exists (select 1 from pg_trigger t
                                            where t.tgrelid = c.oid and not t.tgisinternal
                                              and (t.tgtype & 32) <> 0)
                        """);
                ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }
}
