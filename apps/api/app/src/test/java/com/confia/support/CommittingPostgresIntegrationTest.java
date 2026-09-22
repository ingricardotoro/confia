package com.confia.support;

import com.confia.shared.security.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
        String quotedTableList = tables.stream()
                .map(name -> "\"" + name + "\"")
                .collect(Collectors.joining(", "));
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_owner");
                var statement = connection.createStatement()) {
            statement.execute("truncate table " + quotedTableList);
        }
    }

    private List<String> tablesWithoutABeforeTruncateTrigger() throws SQLException {
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
