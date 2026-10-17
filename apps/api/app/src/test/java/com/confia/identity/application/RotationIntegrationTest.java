package com.confia.identity.application;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.support.SharedPostgresContainer;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;

/**
 * Shared set-up for the refresh-token integration tests (session-tokens-and-web-layer tasks.md 4.1):
 * the real rotation over the real adapters, families and tokens seeded with SQL when a scenario needs
 * a window or a state a real issuance could not reach (an absolute end at 20:00 with the last use at
 * 19:50, a token consumed with its encrypted successor), raw readers of what is stored, and the
 * lock-wait condition of the concurrency tests. Abstract, so the {@code *IT} naming rule does not
 * apply to it.
 */
abstract class RotationIntegrationTest extends SessionIssuanceIntegrationTest {

    static final String AGENT_ONE = "agente-uno";
    static final String AGENT_TWO = "agente-dos";

    /** A token that was consumed, and the successor that replaced it. */
    record ConsumedSeed(PlainRefreshToken consumed, PlainRefreshToken successor) {
    }

    RotateRefreshToken rotation(Clock clock, AuditLogWriter auditLogWriter,
            InstitutionId configuredInstitution) {
        return new RotateRefreshToken(transactionRunner(), () -> configuredInstitution,
                new JooqRefreshTokenFamilyRepository(dsl), new JooqRefreshTokenRepository(dsl),
                accessTokenIssuer(clock), auditLogWriter, clock, new SecureRandom());
    }

    RefreshOutcome rotate(String instant, String presentedToken, String userAgent) {
        return rotation(fixedClock(instant), new JooqAuditLogWriter(dsl), institutionId)
                .execute(context(), new RotateRefreshTokenCommand(presentedToken, userAgent));
    }

    /** A family of {@code account}, with the window and the revocation the scenario names. */
    UUID seedFamily(StaffAccountId account, boolean secondFactor, String createdAt,
            String lastUsedAt, String absoluteEnd, String revokedAt, String revokedReason) {
        UUID familyId = UUID.randomUUID();
        transactionRunner().execute(context(), () -> dsl.execute("""
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified, user_agent_fingerprint,
                     created_at, last_used_at, absolute_expires_at, revoked_at, revoked_reason)
                values (?, ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz),
                        cast(? as timestamptz), cast(? as timestamptz), ?)
                """, institutionId.value(), familyId, account.value(), secondFactor,
                UserAgentFingerprint.of(AGENT_ONE).value(), createdAt, lastUsedAt, absoluteEnd,
                revokedAt, revokedReason));
        return familyId;
    }

    /** An unconsumed token of {@code familyId}; the text returned is the only copy of it. */
    PlainRefreshToken seedOpenToken(UUID familyId, String issuedAt, String expiresAt) {
        PlainRefreshToken token = PlainRefreshToken.generate(new SecureRandom());
        transactionRunner().execute(context(), () -> insertToken(UUID.randomUUID(), familyId, token,
                issuedAt, expiresAt));
        return token;
    }

    /**
     * A token consumed at {@code consumedAt} by {@code consumedByAgent}, with its open successor, and
     * the encrypted successor still in its row when {@code ciphertext} is true.
     */
    ConsumedSeed seedConsumedToken(UUID familyId, String issuedAt, String expiresAt,
            String consumedAt, String consumedByAgent, boolean ciphertext) {
        PlainRefreshToken consumed = PlainRefreshToken.generate(new SecureRandom());
        PlainRefreshToken successor = PlainRefreshToken.generate(new SecureRandom());
        UUID consumedId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        transactionRunner().execute(context(), () -> {
            dsl.execute("""
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at,
                         consumed_at, consumed_user_agent_fingerprint, replaced_by,
                         successor_token_ciphertext)
                    values (?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz),
                            cast(? as timestamptz), ?, ?, ?)
                    """, institutionId.value(), consumedId, familyId,
                    RefreshTokenHash.of(consumed).value(), issuedAt, expiresAt, consumedAt,
                    UserAgentFingerprint.of(consumedByAgent).value(), successorId,
                    ciphertext ? "v1:AAAA" : null);
            return insertToken(successorId, familyId, successor, consumedAt, expiresAt);
        });
        return new ConsumedSeed(consumed, successor);
    }

    private int insertToken(UUID tokenId, UUID familyId, PlainRefreshToken token, String issuedAt,
            String expiresAt) {
        return dsl.execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at)
                values (?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz))
                """, institutionId.value(), tokenId, familyId, RefreshTokenHash.of(token).value(),
                issuedAt, expiresAt);
    }

    Record familyRow(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select created_at, last_used_at, absolute_expires_at, revoked_at, revoked_reason
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    /** The tokens of a family, oldest first. */
    List<Record> tokensOf(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select id, token_hash, issued_at, expires_at, consumed_at,
                       consumed_user_agent_fingerprint, replaced_by, successor_token_ciphertext
                  from identity_refresh_token where institution_id = ? and family_id = ?
                 order by issued_at, id
                """, institutionId.value(), familyId));
    }

    /** Both tables as text, the way a dump would show them: equal before and after means unchanged. */
    String storedSessions() {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select row_to_json(f)::text from identity_refresh_token_family f
                union all
                select row_to_json(t)::text from identity_refresh_token t
                order by 1
                """).formatCSV());
    }

    static Instant instantOf(Record record, String column) {
        return record.get(column, OffsetDateTime.class).toInstant();
    }

    /** Waits, with a bound, for a test to let go of a transaction it is holding at a chosen point. */
    static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the held transaction was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Whether a backend of the database is waiting for a row lock whose statement names {@code
     * table}, read as the superuser through its own connection. It is a condition, never a pause:
     * callers poll it with a bound ({@link #holdsWithin}).
     */
    static boolean someBackendWaitsForALockOn(String table) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select count(*) from pg_stat_activity
                         where datname = current_database() and pid <> pg_backend_pid()
                           and wait_event_type = 'Lock' and query ilike ?
                        """)) {
            statement.setString(1, "%" + table + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
