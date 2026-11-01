package com.confia.shared.jobs;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Rows for the tests of the idempotency and backoff purges, written and read with the superuser of the
 * container (which row-level security does not bind), so a test can seed two institutions and read both
 * without opening a context for each. Every instant is the one the test chooses, usually read from the
 * database clock, so a boundary is exact.
 */
public final class PurgeProbeRows {

    /** The tables these helpers count; a typo cannot reach a statement. */
    private static final List<String> TABLES = List.of("shared_idempotency_key", "identity_login_backoff",
            "identity_mfa_totp_backoff");

    private PurgeProbeRows() {
    }

    /** {@code count} idempotency keys of {@code institution} that expire at {@code expiresAt}. */
    public static void idempotencyKeys(UUID institution, int count, Instant expiresAt) {
        run("""
                insert into shared_idempotency_key
                    (institution_id, endpoint, idempotency_key, request_hash, status,
                     response_status, response_body, created_at, completed_at, expires_at)
                select ?, '/api/v1/purge-probe', gen_random_uuid()::text, repeat('0', 64), 'COMPLETED',
                       200, '{}'::jsonb, cast(? as timestamptz) - interval '1 hour',
                       cast(? as timestamptz) - interval '30 minutes', cast(? as timestamptz)
                  from generate_series(1, ?)
                """, institution, at(expiresAt), at(expiresAt), at(expiresAt), count);
    }

    /** {@code count} login backoff rows of {@code institution}, last attempted at {@code lastAttemptAt}. */
    public static void loginBackoff(UUID institution, int count, Instant lastAttemptAt) {
        run("""
                insert into identity_login_backoff
                    (institution_id, identifier_hash, consecutive_failures, last_attempt_at)
                select ?, replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', ''),
                       3, cast(? as timestamptz)
                  from generate_series(1, ?)
                """, institution, at(lastAttemptAt), count);
    }

    /** {@code count} TOTP backoff rows of {@code institution}, each of a new account. */
    public static void mfaTotpBackoff(UUID institution, int count, Instant lastAttemptAt) {
        for (int i = 0; i < count; i++) {
            UUID account = UUID.randomUUID();
            run("""
                    insert into identity_staff_account (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA', false)
                    """, institution, account, "purga." + account + "@colegio.edu.hn");
            run("""
                    insert into identity_mfa_totp_backoff
                        (institution_id, account_id, consecutive_failures, last_attempt_at)
                    values (?, ?, 2, cast(? as timestamptz))
                    """, institution, account, at(lastAttemptAt));
        }
    }

    public static long count(String table, UUID institution) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException(table);
        }
        return scalar("select count(*) from " + table + " where institution_id = ?", institution);
    }

    /** The audit entries of {@code institution} with this action, oldest first, as the text of {@code after_value}. */
    public static List<String> auditDetails(UUID institution, String action) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select after_value::text from shared_audit_log where institution_id = ? and action = ? "
                                + "order by id")) {
            statement.setObject(1, institution);
            statement.setString(2, action);
            try (ResultSet result = statement.executeQuery()) {
                List<String> details = new java.util.ArrayList<>();
                while (result.next()) {
                    details.add(result.getString(1));
                }
                return details;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void run(String sql, Object... parameters) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long scalar(String sql, Object... parameters) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }
}
