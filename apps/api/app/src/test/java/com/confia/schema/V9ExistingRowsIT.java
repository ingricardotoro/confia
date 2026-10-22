package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI42 (session-endpoints-cookie-and-csrf specs/build-integrity/spec.md): the families
 * revoked before {@code V9} with {@code reuse_detected} and {@code password_change}, and a live one,
 * are still there after {@code V9} with their reason and their instant, and the live one can now be
 * revoked with a reason that arrived with the migration. The rows are written as the application
 * role under row-level security in a database that has only {@code V8}, then {@code V9} is applied
 * on top of them, which is how a deployment meets it.
 */
class V9ExistingRowsIT {

    private static final String PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final String FINGERPRINT = "0".repeat(64);
    private static final Instant REUSED_AT = Instant.parse("2026-10-15T08:00:00Z");
    private static final Instant PASSWORD_AT = Instant.parse("2026-10-15T09:00:00Z");

    @Test
    void familiesRevokedBeforeV9SurviveItWithTheirReasonAndTheirInstant() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID reused = UUID.randomUUID();
        UUID changed = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("8")) {
            try (Connection connection = database.connectionAs("confia_admin_app")) {
                connection.setAutoCommit(false);
                context(connection, institution);
                try (PreparedStatement insert = connection.prepareStatement("""
                        insert into identity_staff_account
                            (institution_id, id, email, password_hash, mfa_required)
                        values (?, ?, ?, ?, false)
                        """)) {
                    insert.setObject(1, institution);
                    insert.setObject(2, account);
                    insert.setString(3, "ana.martinez@colegio.edu.hn");
                    insert.setString(4, PASSWORD_HASH);
                    insert.executeUpdate();
                }
                insertFamily(connection, institution, account, reused, REUSED_AT, "reuse_detected");
                insertFamily(connection, institution, account, changed, PASSWORD_AT,
                        "password_change");
                insertFamily(connection, institution, account, live, null, null);
                connection.commit();
            }

            database.migrate("9");

            try (Connection connection = database.connectionAs("confia_admin_app")) {
                connection.setAutoCommit(false);
                context(connection, institution);
                Map<UUID, String> reasons = new HashMap<>();
                Map<UUID, Instant> instants = new HashMap<>();
                try (PreparedStatement select = connection.prepareStatement("""
                        select id, revoked_reason, revoked_at from identity_refresh_token_family
                         where institution_id = ?
                        """)) {
                    select.setObject(1, institution);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            UUID id = rows.getObject(1, UUID.class);
                            OffsetDateTime revokedAt = rows.getObject(3, OffsetDateTime.class);
                            reasons.put(id, rows.getString(2));
                            instants.put(id, revokedAt == null ? null : revokedAt.toInstant());
                        }
                    }
                }
                assertThat(reasons).hasSize(3);
                assertThat(reasons.get(reused)).isEqualTo("reuse_detected");
                assertThat(instants.get(reused)).isEqualTo(REUSED_AT);
                assertThat(reasons.get(changed)).isEqualTo("password_change");
                assertThat(instants.get(changed)).isEqualTo(PASSWORD_AT);
                assertThat(reasons.get(live)).isNull();
                assertThat(instants.get(live)).isNull();

                try (PreparedStatement revoke = connection.prepareStatement("""
                        update identity_refresh_token_family
                           set revoked_at = now(), revoked_reason = 'logout'
                         where institution_id = ? and id = ?
                        """)) {
                    revoke.setObject(1, institution);
                    revoke.setObject(2, live);
                    assertThat(revoke.executeUpdate())
                            .as("the live family accepts a reason that arrived with V9")
                            .isEqualTo(1);
                }
                connection.rollback();
            }
        }
    }

    private static void context(Connection connection, UUID institution) throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institution.toString());
            statement.execute();
        }
    }

    private static void insertFamily(Connection connection, UUID institution, UUID account,
            UUID family, Instant revokedAt, String reason) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified,
                     user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                     revoked_at, revoked_reason)
                values (?, ?, ?, false, ?, cast('2026-10-15T07:00:00Z' as timestamptz),
                        cast('2026-10-15T07:00:00Z' as timestamptz),
                        cast('2026-10-15T19:00:00Z' as timestamptz),
                        cast(? as timestamptz), ?)
                """)) {
            insert.setObject(1, institution);
            insert.setObject(2, family);
            insert.setObject(3, account);
            insert.setString(4, FINGERPRINT);
            insert.setString(5, revokedAt == null ? null : revokedAt.toString());
            insert.setString(6, reason);
            insert.executeUpdate();
        }
    }
}
