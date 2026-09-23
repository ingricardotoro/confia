package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * Real SQL statements against {@code shared_idempotency_key}, proving the two role-scoped
 * scenarios {@code RolePrivilegeMatrixIT}'s catalogue matrix predicts (idempotency-key-
 * infrastructure design.md decision 3; specs/build-integrity/spec.md, requirements "Permisos de
 * acceso a {@code shared_idempotency_key} por rol de base de datos" and "Ausencia de acceso del
 * portal a {@code shared_idempotency_key} (brecha con destino: F3/F4)").
 *
 * <p>{@code confia_admin_app} (this class's own pooled {@link #dsl}, autowired by {@code
 * PostgresIntegrationTest}) can {@code SELECT}, {@code INSERT} and {@code UPDATE}, but {@code
 * DELETE} is rejected by ordinary {@code GRANT}/{@code REVOKE} — there is no append-only trigger
 * on this table, so this is the only barrier. {@code confia_portal_app} is rejected on all four,
 * exercised over a raw connection since the pooled {@link #dsl} never authenticates as that role.
 */
class IdempotencyKeyPrivilegeIT extends CommittingPostgresIntegrationTest {

    /** Satisfies the {@code shared_idempotency_key_request_hash_chk} regex without hashing. */
    private static final String VALID_REQUEST_HASH = "0".repeat(64);
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDelete() {
        UUID institutionId = UUID.randomUUID();
        String endpoint = "/api/v1/payments";
        String idempotencyKey = UUID.randomUUID().toString();

        long visibleAfterInsert = transactionRunner().execute(contextOf(institutionId), () -> {
            insertOneRow(institutionId, endpoint, idempotencyKey);
            return dsl.fetchOne("select count(*) as c from shared_idempotency_key where "
                    + "institution_id = ? and endpoint = ? and idempotency_key = ?", institutionId,
                    endpoint, idempotencyKey).get("c", Number.class).longValue();
        });
        assertThat(visibleAfterInsert)
                .as("confia_admin_app must be able to both INSERT and SELECT")
                .isEqualTo(1L);

        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    update shared_idempotency_key set expires_at = expires_at + interval '1 hour'
                    where institution_id = ? and endpoint = ? and idempotency_key = ?
                    """, institutionId, endpoint, idempotencyKey);
            return null;
        });

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    delete from shared_idempotency_key
                    where institution_id = ? and endpoint = ? and idempotency_key = ?
                    """, institutionId, endpoint, idempotencyKey);
            return null;
        })).as("confia_admin_app has no DELETE privilege on shared_idempotency_key")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void confiaPortalAppIsRejectedOnAllFourOperations() throws SQLException {
        UUID institutionId = UUID.randomUUID();
        String endpoint = "/api/v1/payments";
        String idempotencyKey = UUID.randomUUID().toString();

        try (Connection connection = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            connection.setAutoCommit(false);

            setInstitutionContext(connection, institutionId);
            assertRejected(connection,
                    "select count(*) from shared_idempotency_key where institution_id = ?",
                    institutionId);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, """
                    insert into shared_idempotency_key
                        (institution_id, endpoint, idempotency_key, request_hash, status,
                         expires_at)
                    values (?, ?, ?, ?, 'IN_PROGRESS', now() + interval '1 hour')
                    """, institutionId, endpoint, idempotencyKey, VALID_REQUEST_HASH);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, """
                    update shared_idempotency_key set expires_at = now()
                    where institution_id = ? and endpoint = ? and idempotency_key = ?
                    """, institutionId, endpoint, idempotencyKey);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, """
                    delete from shared_idempotency_key
                    where institution_id = ? and endpoint = ? and idempotency_key = ?
                    """, institutionId, endpoint, idempotencyKey);
            connection.rollback();
        }
    }

    private void insertOneRow(UUID institutionId, String endpoint, String idempotencyKey) {
        dsl.execute("""
                insert into shared_idempotency_key
                    (institution_id, endpoint, idempotency_key, request_hash, status, expires_at)
                values (?, ?, ?, ?, 'IN_PROGRESS', now() + interval '1 hour')
                """, institutionId, endpoint, idempotencyKey, VALID_REQUEST_HASH);
    }

    private static void setInstitutionContext(Connection connection, UUID institutionId)
            throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institutionId.toString());
            statement.execute();
        }
    }

    private static void assertRejected(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            assertThatThrownBy(statement::execute)
                    .as("confia_portal_app must have no privilege on shared_idempotency_key")
                    .isInstanceOf(SQLException.class)
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }
}
