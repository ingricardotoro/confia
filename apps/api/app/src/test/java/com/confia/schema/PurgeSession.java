package com.confia.schema;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One open transaction of a real database role, for the purge-function tests
 * (background-jobs-with-db-scheduler design.md decision 16; tasks.md, "database clock rules"). The
 * transaction is never committed by default: it is rolled back on close, so a test leaves no row
 * behind. {@link #now()} reads {@code now()} of PostgreSQL once; inside one transaction it never
 * moves, so the {@code now()} the purge function evaluates is that same instant and every boundary
 * is exact to the microsecond, with no Java clock involved.
 */
final class PurgeSession implements AutoCloseable {

    /** Hex of a SHA-256 fingerprint, all zeros, valid for the table checks. */
    static final String ZERO_FINGERPRINT = "0".repeat(64);

    private static final String PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private final Connection connection;
    private final OffsetDateTime now;

    private PurgeSession(Connection connection) throws SQLException {
        this.connection = connection;
        this.connection.setAutoCommit(false);
        // "30 days" is a calendar interval in the session zone: pin the zone so a daylight-saving
        // zone of the JVM cannot move the floor by an hour.
        try (PreparedStatement zone = connection.prepareStatement("set time zone 'UTC'")) {
            zone.execute();
        }
        this.now = scalarInstant("select now()");
    }

    /** A session of {@code role} on the shared container's database. */
    static PurgeSession open(String role) throws SQLException {
        return new PurgeSession(SharedPostgresContainer.connectionAs(role));
    }

    /** A session of {@code role} on a throw-away database. */
    static PurgeSession open(StepwiseDatabase database, String role) throws SQLException {
        return new PurgeSession(database.connectionAs(role));
    }

    Connection connection() {
        return connection;
    }

    /** {@code now()} of the database, fixed for the whole transaction. */
    OffsetDateTime now() {
        return now;
    }

    /** Sets the institution and the actor of this transaction, as the worker does. */
    PurgeSession asSystemOf(UUID institutionId) throws SQLException {
        return context(institutionId).actor("system");
    }

    PurgeSession context(UUID institutionId) throws SQLException {
        setting("app.institution_id", institutionId == null ? "" : institutionId.toString());
        return this;
    }

    PurgeSession actor(String kind) throws SQLException {
        setting("app.actor_kind", kind);
        return this;
    }

    private void setting(String name, String value) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.execute();
        }
    }

    UUID seedAccount(UUID institutionId) throws SQLException {
        UUID accountId = UUID.randomUUID();
        execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, false)
                """, institutionId, accountId, "purge." + accountId + "@colegio.edu.hn",
                PASSWORD_HASH);
        return accountId;
    }

    /** A password-reset token that expired at {@code expiresAt}; superseded, so it is not open. */
    UUID seedResetToken(UUID institutionId, UUID accountId, OffsetDateTime expiresAt)
            throws SQLException {
        UUID tokenId = UUID.randomUUID();
        OffsetDateTime issuedAt = expiresAt.minus(30, ChronoUnit.MINUTES);
        execute("""
                insert into identity_password_reset_token
                    (institution_id, id, account_id, token_hash, issued_at, expires_at,
                     superseded_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, institutionId, tokenId, accountId, hash(tokenId), issuedAt, expiresAt,
                issuedAt);
        return tokenId;
    }

    /**
     * A family with the given instants. {@code revokedAt} may be null (a live family); the creation
     * instant is a day before the earliest instant given so every table check holds.
     */
    UUID seedFamily(UUID institutionId, UUID accountId, OffsetDateTime revokedAt,
            OffsetDateTime absoluteExpiresAt, OffsetDateTime lastUsedAt) throws SQLException {
        UUID familyId = UUID.randomUUID();
        OffsetDateTime earliest = absoluteExpiresAt.isBefore(lastUsedAt)
                ? absoluteExpiresAt : lastUsedAt;
        if (revokedAt != null && revokedAt.isBefore(earliest)) {
            earliest = revokedAt;
        }
        OffsetDateTime createdAt = earliest.minus(1, ChronoUnit.DAYS);
        execute("""
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified,
                     user_agent_fingerprint, created_at, last_used_at, absolute_expires_at,
                     revoked_at, revoked_reason)
                values (?, ?, ?, false, ?, ?, ?, ?, ?, ?)
                """, institutionId, familyId, accountId, ZERO_FINGERPRINT, createdAt, lastUsedAt,
                absoluteExpiresAt, revokedAt, revokedAt == null ? null : "password_change");
        return familyId;
    }

    /** An open token of the family. */
    UUID seedOpenToken(UUID institutionId, UUID familyId) throws SQLException {
        UUID tokenId = UUID.randomUUID();
        execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at)
                values (?, ?, ?, ?, now(), now() + interval '8 hours')
                """, institutionId, tokenId, familyId, hash(tokenId));
        return tokenId;
    }

    /**
     * A consumed token whose successor is {@code successorId}. The successor key is deferred, so the
     * successor may be inserted after this row.
     */
    UUID seedConsumedToken(UUID institutionId, UUID familyId, UUID successorId)
            throws SQLException {
        UUID tokenId = UUID.randomUUID();
        execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at, consumed_at,
                     consumed_user_agent_fingerprint, replaced_by)
                values (?, ?, ?, ?, now() - interval '2 hours', now() + interval '8 hours',
                        now() - interval '1 hour', ?, ?)
                """, institutionId, tokenId, familyId, hash(tokenId), ZERO_FINGERPRINT,
                successorId);
        return tokenId;
    }

    /** A token id as a distinct 64-hex string, unique per token. */
    private static String hash(UUID tokenId) {
        String hex = tokenId.toString().replace("-", "");
        return hex + hex;
    }

    int execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    long count(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private OffsetDateTime scalarInstant(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, OffsetDateTime.class);
        }
    }

    /**
     * Runs the statement behind a savepoint and returns the failure it raised, or null when it
     * succeeded. The transaction survives the failure.
     */
    Failure failureOf(String sql, Object... parameters) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
            connection.releaseSavepoint(savepoint);
            return null;
        } catch (SQLException failure) {
            connection.rollback(savepoint);
            return new Failure(failure.getSQLState(), failure.getMessage());
        }
    }

    /** What a refused statement reported. */
    record Failure(String sqlState, String message) {
    }

    void commit() throws SQLException {
        connection.commit();
    }

    private static void bind(PreparedStatement statement, Object[] parameters)
            throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }
}
