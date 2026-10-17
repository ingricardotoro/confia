package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.support.SharedPostgresContainer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Shared set-up for the session-issuance integration tests (session-tokens-and-web-layer tasks.md
 * 3.3): the real adapters and a real {@link IssueSession} whose access tokens are signed with a key
 * pair generated for the run (no key is versioned, regla 13). Abstract, so the {@code *IT} naming
 * rule does not apply to it.
 */
abstract class SessionIssuanceIntegrationTest extends PasswordResetIntegrationTest {

    static final String ANA = "ana.martinez@colegio.edu.hn";
    static final String CURRENT_PASSWORD = "Cafetal de Copán 2026";
    private static final String KID = "admin-2026a";
    private static final KeyPair KEYS = generatedPair();
    private static final CompactJws JWS = new CompactJws(
            SigningKeyRing.of(SigningKey.signing(KID, KEYS.getPublic(), KEYS.getPrivate())));

    IssueSession issueSession(String instant) {
        return issueSession(instant, new JooqAuditLogWriter(dsl),
                new JooqRefreshTokenRepository(dsl));
    }

    IssueSession issueSession(String instant, AuditLogWriter auditLogWriter,
            RefreshTokenRepository tokens) {
        return issueSession(fixedClock(instant), auditLogWriter, tokens);
    }

    IssueSession issueSession(Clock clock, AuditLogWriter auditLogWriter,
            RefreshTokenRepository tokens) {
        return new IssueSession(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqRefreshTokenFamilyRepository(dsl), tokens,
                new AccessTokenIssuer(JWS, clock), auditLogWriter, clock, new SecureRandom());
    }

    /**
     * A token repository whose insert is the test's own and whose other operations are those of
     * {@code real}: the way a test holds or fails the write of a token without re-implementing the rest.
     */
    static RefreshTokenRepository withInsert(RefreshTokenRepository real,
            BiConsumer<InstitutionId, RefreshTokenRow> insert) {
        return new RefreshTokenRepository() {
            @Override
            public void insert(InstitutionId institutionId, RefreshTokenRow row) {
                insert.accept(institutionId, row);
            }

            @Override
            public Optional<RefreshTokenState> findByHash(InstitutionId institutionId,
                    RefreshTokenHash hash) {
                return real.findByHash(institutionId, hash);
            }

            @Override
            public Optional<RefreshTokenState> findById(InstitutionId institutionId, UUID tokenId) {
                return real.findById(institutionId, tokenId);
            }

            @Override
            public boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
                    UserAgentFingerprint presenter, UUID successorId) {
                return real.consume(institutionId, tokenId, now, presenter, successorId);
            }

            @Override
            public void clearSuccessors(InstitutionId institutionId, UUID familyId) {
                real.clearSuccessors(institutionId, familyId);
            }
        };
    }

    AccessTokenVerifier verifierAt(String instant) {
        return new AccessTokenVerifier(JWS, fixedClock(instant));
    }

    IssuedSession issue(String instant, StaffAccountId account) {
        return issueSession(instant).execute(context(),
                new IssueSessionCommand(account, false, null, null));
    }

    static IssueSessionCommand commandFrom(StaffAccountId account, boolean secondFactor,
            String userAgent, String address) {
        return new IssueSessionCommand(account, secondFactor, userAgent,
                address == null ? null : ClientAddress.parseLiteral(address));
    }

    /**
     * Whether a backend of the database is waiting for a row lock on the account table, read as the
     * superuser through its own connection. It is a condition, never a pause: callers poll it with a
     * bound, and the test goes on the moment it holds.
     */
    static boolean someBackendWaitsForTheAccountLock() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select count(*) from pg_stat_activity
                         where datname = current_database() and pid <> pg_backend_pid()
                           and wait_event_type = 'Lock' and query ilike '%identity_staff_account%'
                        """);
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Polls {@code condition} until it holds or {@code limitMillis} pass; reports which. */
    static boolean holdsWithin(long limitMillis, BooleanSupplier condition) {
        long deadline = System.nanoTime() + limitMillis * 1_000_000L;
        CountDownLatch never = new CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                never.await(5, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return condition.getAsBoolean();
    }

    /** A clock a test moves by hand, readable from any thread: no test of time sleeps or reads a clock. */
    static final class SteppedClock extends Clock {

        private volatile Instant now;

        SteppedClock(String instant) {
            this.now = Instant.parse(instant);
        }

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static KeyPair generatedPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
