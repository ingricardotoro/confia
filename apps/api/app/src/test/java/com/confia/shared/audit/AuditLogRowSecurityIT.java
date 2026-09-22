package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-level security on {@code shared_audit_log}, by institution (design.md decision 7;
 * specs/audit-trail/spec.md, requirement "Seguridad a nivel de fila por institución en {@code
 * shared_audit_log}").
 */
class AuditLogRowSecurityIT extends CommittingPostgresIntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void oneInstitutionCannotReadAnothersRows() {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        seedOneRow(institutionA);
        seedOneRow(institutionB);

        long visibleToA = transactionRunner().execute(contextOf(institutionA),
                () -> dsl.fetchOne("select count(*) as c from shared_audit_log")
                        .get("c", Number.class).longValue());

        assertThat(visibleToA)
                .as("institution A's context must see exactly its own row, never institution B's, "
                        + "although both physically exist in the table")
                .isEqualTo(1L);
    }

    @Test
    void absentContextDeniesReturningZeroRowsInsteadOfAConversionError() {
        UUID institutionId = UUID.randomUUID();
        seedOneRow(institutionId);

        // A freshly-drawn pooled connection never had app.institution_id set at all: current_setting
        // returns NULL and the policy denies by default, no ::uuid cast ever attempted.
        long visible = dsl.fetchOne("select count(*) as c from shared_audit_log")
                .get("c", Number.class).longValue();

        assertThat(visible).isZero();
    }

    @Test
    void emptyContextDeniesReturningZeroRowsInsteadOfAConversionError() throws SQLException {
        UUID institutionId = UUID.randomUUID();
        seedOneRow(institutionId);

        // set_config(..., true) is local to the current transaction: both statements must run on
        // one explicit, uncommitted transaction over the same raw connection, or the empty-string
        // setting would already be gone before the SELECT runs (same trap PostgresIntegrationTest's
        // withInstitutionContext Javadoc documents for this class's family of *IT.java tests).
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            connection.setAutoCommit(false);
            try (PreparedStatement setEmpty = connection
                    .prepareStatement("select set_config('app.institution_id', '', true)")) {
                setEmpty.execute();
            }
            try (PreparedStatement count = connection
                    .prepareStatement("select count(*) as c from shared_audit_log");
                    ResultSet resultSet = count.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong("c")).isZero();
            }
            connection.rollback();
        }
    }

    /**
     * Inserts one row with explicit, deliberately arbitrary {@code id}, {@code prev_hash} and
     * {@code row_hash} (this cut's migration does not chain them yet — PR B2b does), committed
     * through the real {@link #transactionRunner()} so it is visible outside its own transaction.
     */
    private void seedOneRow(UUID institutionId) {
        byte[] prevHash = new byte[32];
        byte[] rowHash = new byte[32];
        RANDOM.nextBytes(rowHash);
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into shared_audit_log
                        (id, institution_id, actor_kind, actor_label, request_id, action,
                         entity_type, entity_id, outcome, prev_hash, row_hash)
                    values (1, ?, 'system', 'test actor', ?, 'test.action', 'test_entity',
                        'entity-1', 'success', ?, ?)
                    """, institutionId, UUID.randomUUID(), prevHash, rowHash);
            return null;
        });
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }
}
