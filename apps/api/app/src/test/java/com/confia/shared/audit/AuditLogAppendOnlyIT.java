package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * Append-only enforcement of {@code shared_audit_log} (design.md decisions 7 and 8;
 * specs/audit-trail/spec.md, requirement "Inmutabilidad de {@code shared_audit_log}, incluso para
 * el propietario del esquema"; specs/build-integrity/spec.md, requirement "Puertas de catálogo y de
 * privilegios extendidas a {@code shared_audit_log}").
 *
 * <p>Two independent barriers are exercised, deliberately with two different connecting roles:
 * {@code confia_admin_app} (this class's own pooled {@link #dsl}, the application role) has no
 * {@code UPDATE}, {@code DELETE} or {@code TRUNCATE} privilege at all, so those three are rejected
 * by ordinary {@code GRANT}/{@code REVOKE} before the trigger is ever reached. {@code confia_owner},
 * the schema owner, is <em>not</em> subject to {@code GRANT}/{@code REVOKE} and retains every
 * privilege by definition (verified separately by {@code RolePrivilegeMatrixIT}); only the {@code
 * shared_audit_is_append_only()} trigger rejects it, which is the whole point of the second barrier
 * (design.md decision 8: "el rechazo al propietario lo demuestra otra prueba distinta"). Both
 * PostgreSQL mechanisms raise {@code SQLSTATE 42501} ({@code insufficient_privilege}), so both
 * assertions check the same error code.
 */
class AuditLogAppendOnlyIT extends CommittingPostgresIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Test
    void confiaAdminAppCanSelectAndInsertButUpdateDeleteAndTruncateAreRejected() {
        UUID institutionId = UUID.randomUUID();
        long visibleAfterInsert = transactionRunner().execute(contextOf(institutionId), () -> {
            insertOneRow(institutionId, 1L);
            return dsl.fetchOne("select count(*) as c from shared_audit_log")
                    .get("c", Number.class).longValue();
        });
        assertThat(visibleAfterInsert)
                .as("confia_admin_app must be able to both SELECT and INSERT")
                .isEqualTo(1L);

        assertThatThrownBy(() -> dsl.execute(
                "update shared_audit_log set reason = ? where institution_id = ? and id = ?",
                "attempt", institutionId, 1L))
                .as("confia_admin_app has no UPDATE privilege on shared_audit_log")
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> dsl.execute(
                "delete from shared_audit_log where institution_id = ? and id = ?", institutionId,
                1L))
                .as("confia_admin_app has no DELETE privilege on shared_audit_log")
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> dsl.execute("truncate table shared_audit_log"))
                .as("confia_admin_app has no TRUNCATE privilege on shared_audit_log")
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * The three scenarios the user's own review explicitly asked to see proven, not assumed: the
     * append-only trigger rejects {@code UPDATE}, {@code DELETE} <em>and</em> {@code TRUNCATE} even
     * for {@code confia_owner}, the schema owner — who is exempt from every {@code GRANT}/{@code
     * REVOKE} and would otherwise be free to erase the evidence a role-based check alone could never
     * stop.
     */
    @Test
    void confiaOwnerUpdateDeleteAndTruncateAreAllRejectedByTheAppendOnlyTrigger()
            throws SQLException {
        UUID institutionId = UUID.randomUUID();
        transactionRunner().execute(contextOf(institutionId), () -> {
            insertOneRow(institutionId, 1L);
            return null;
        });

        try (Connection connection = SharedPostgresContainer.connectionAs("confia_owner")) {
            connection.setAutoCommit(false);

            setInstitutionContext(connection, institutionId);
            assertRejectedWithInsufficientPrivilege(connection,
                    "update shared_audit_log set reason = ? where institution_id = ? and id = ?",
                    "attempt", institutionId, 1L);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejectedWithInsufficientPrivilege(connection,
                    "delete from shared_audit_log where institution_id = ? and id = ?",
                    institutionId, 1L);
            connection.rollback();

            // TRUNCATE fires a statement-level trigger, unaffected by row-level-security context.
            assertRejectedWithInsufficientPrivilege(connection, "truncate table shared_audit_log");
            connection.rollback();
        }
    }

    private static void setInstitutionContext(Connection connection, UUID institutionId)
            throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institutionId.toString());
            statement.execute();
        }
    }

    private static void assertRejectedWithInsufficientPrivilege(Connection connection, String sql,
            Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            assertThatThrownBy(statement::executeUpdate)
                    .as("the append-only trigger must reject this statement even for confia_owner")
                    .isInstanceOf(SQLException.class)
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    /**
     * Inserts one row with explicit, deliberately arbitrary {@code id}, {@code prev_hash} and
     * {@code row_hash}: this cut's migration (task 2.2) does not populate those by trigger yet —
     * that is PR B2b's own {@code shared_audit_log_chain()} — so every direct insert in this class
     * must supply values that satisfy the table's {@code CHECK} constraints (32-byte hashes) on its
     * own. Runs over {@link #dsl}, inherited from {@code PostgresIntegrationTest} and autowired as
     * {@code confia_admin_app}.
     */
    private void insertOneRow(UUID institutionId, long id) {
        byte[] prevHash = new byte[32];
        byte[] rowHash = new byte[32];
        RANDOM.nextBytes(rowHash);
        dsl.execute("""
                insert into shared_audit_log
                    (id, institution_id, actor_kind, actor_label, request_id, action, entity_type,
                     entity_id, outcome, prev_hash, row_hash)
                values (?, ?, 'system', 'test actor', ?, 'test.action', 'test_entity', 'entity-1',
                    'success', ?, ?)
                """, id, institutionId, UUID.randomUUID(), prevHash, rowHash);
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }
}
