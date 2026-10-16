package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The closing condition of task 3.3 (review of PR 9, I-2; session-tokens-and-web-layer design.md
 * decisions 9 and 12): a password reset and a simultaneous issuance, on two real transactions, leave
 * no live family that was issued with the old password. With READ COMMITTED, {@code FOR UPDATE} on the
 * families alone would not stop an issuance from inserting a family while the reset runs; the
 * issuance therefore takes the same account lock the reset takes, before it inserts anything, and the
 * lock order is always account, then families.
 *
 * <p>No clock decides any order. The first transaction is held with a {@link CountDownLatch} at the
 * exact point under test, the second is started, and the test goes on when the database reports a
 * backend waiting for a row lock, which is a condition with a bound, not a pause. If the issuance
 * did not take the account lock first, the second test would fail: the issuance would not wait on the
 * account row but on its own insert, and the instant of the session would be read before the wait,
 * so before the reset it queued behind.
 *
 * <p><b>What the first test proves, and what it cannot.</b> The foreign key from the family to the
 * account takes {@code FOR KEY SHARE} on the account row when the family is inserted, and that lock
 * conflicts with the reset's {@code FOR UPDATE}. So the database already makes a reset wait for an
 * issuance that has written its family, whether or not the issuance locked the account itself, and
 * the first test stays green without the explicit lock. It is still the property the closing
 * condition names (no family issued before a committed reset survives it), and the explicit lock
 * keeps it true without depending on that foreign key. The deliberate demonstration of the lock is
 * the second test, which fails without it.
 */
class SessionIssuanceResetConcurrencyIT extends SessionIssuanceIntegrationTest {

    private static final String NEW_PASSWORD = "Lempira y maíz 2026";
    private static final long LIMIT_MILLIS = 20_000;
    private static final SecondFactorProof NONE = new SecondFactorProof.None();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The issuance holds the account lock with its family written and not yet committed. The reset
     * starts, must wait for the lock, and when it gets it the committed family is there to revoke.
     */
    @Test
    void aFamilyBeingIssuedWhenTheResetStartsIsRevokedByTheReset() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JooqRefreshTokenRepository real = new JooqRefreshTokenRepository(dsl);
        RefreshTokenRepository holdingBeforeCommit = (institution, row) -> {
            real.insert(institution, row);
            written.countDown();
            awaitRelease(release);
        };

        IssuedSession issued;
        ResetPasswordDecision decision;
        boolean resetWaited;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<IssuedSession> issuing = pool.submit(() -> issueSession("2026-10-15T15:04:00Z",
                    new JooqAuditLogWriter(dsl), holdingBeforeCommit).execute(context(),
                    commandFrom(ana, false, null, null)));
            assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ResetPasswordDecision> resetting = pool.submit(() -> resetAt(token.value(),
                    NEW_PASSWORD, NONE, "2026-10-15T15:05:00Z"));
            resetWaited = holdsWithin(LIMIT_MILLIS,
                    () -> resetting.isDone() || someBackendWaitsForTheAccountLock())
                    && !resetting.isDone();
            release.countDown();
            issued = issuing.get(60, TimeUnit.SECONDS);
            decision = resetting.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(liveFamilies(ana))
                .as("no family issued with the old password survives the committed reset").isZero();
        Record family = family(issued.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("password_change");
        assertThat(family.get("revoked_at", OffsetDateTime.class).toInstant())
                .isEqualTo(Instant.parse("2026-10-15T15:05:00Z"));
        assertThat(revokedCountOfTheEntry()).isEqualTo(1);
        assertThat(resetWaited)
                .as("the reset waited for the account lock the issuance held").isTrue();
    }

    /**
     * The reset holds the account lock with the hash replaced and the families revoked, not yet
     * committed. The issuance starts and must wait; the family it then writes is a session of the
     * new password, and the reset, which ran before it, did not touch it.
     */
    @Test
    void anIssuanceStartedWhileTheResetIsRunningWaitsForItAndIsNotRevoked() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession earlier = issue("2026-10-15T14:00:00Z", ana);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JooqAccountSessionRevoker real = new JooqAccountSessionRevoker(dsl);
        AccountSessionRevoker holdingBeforeCommit = (institution, account, reason, now) -> {
            int count = real.revokeAllLive(institution, account, reason, now);
            revoked.countDown();
            awaitRelease(release);
            return count;
        };

        SteppedClock issuanceClock = new SteppedClock("2026-10-15T15:04:00Z");
        IssuedSession later;
        ResetPasswordDecision decision;
        boolean issuanceWaited;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResetPasswordDecision> resetting = pool.submit(() -> resetUseCase(
                    "2026-10-15T15:05:00Z", new JooqAuditLogWriter(dsl),
                    new JooqPasswordResetTokenRepository(dsl), institutionId, holdingBeforeCommit)
                    .execute(context(), new ResetPasswordCommand(token.value(), NEW_PASSWORD,
                            NONE)));
            assertThat(revoked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<IssuedSession> issuing = pool.submit(() -> issueSession(issuanceClock,
                    new JooqAuditLogWriter(dsl), new JooqRefreshTokenRepository(dsl))
                    .execute(context(), commandFrom(ana, false, null, null)));
            issuanceWaited = holdsWithin(LIMIT_MILLIS,
                    () -> issuing.isDone() || someBackendWaitsForTheAccountLock())
                    && !issuing.isDone();
            // The reset commits at 15:05; the session that waited for it opens after that instant.
            issuanceClock.set("2026-10-15T15:06:00Z");
            release.countDown();
            decision = resetting.get(60, TimeUnit.SECONDS);
            later = issuing.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(family(earlier.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("password_change");
        assertThat(family(later.sessionId()).get("revoked_at"))
                .as("a session opened after the reset committed is the new password's").isNull();
        assertThat(family(later.sessionId()).get("created_at", OffsetDateTime.class).toInstant())
                .as("the instant of that session is read after the lock, so after the reset")
                .isEqualTo(Instant.parse("2026-10-15T15:06:00Z"));
        assertThat(liveFamilies(ana)).isEqualTo(1);
        assertThat(revokedCountOfTheEntry()).isEqualTo(1);
        assertThat(issuanceWaited)
                .as("the issuance waited for the account lock the reset held").isTrue();
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the held transaction was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long liveFamilies(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select count(*) from identity_refresh_token_family
                 where institution_id = ? and account_id = ? and revoked_at is null
                """, institutionId.value(), account.value()).get(0, Long.class));
    }

    private Record family(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select created_at, revoked_at, revoked_reason from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    /** The {@code families} count the reset wrote in its {@code identity.session.revoked} entry. */
    private int revokedCountOfTheEntry() {
        return JSON.readTree(auditRows("identity.session.revoked").get(0).afterValue())
                .get("families").asInt();
    }
}
