package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The closing condition of task 4.1 (review of PR 9, I-2; session-tokens-and-web-layer design.md
 * decisions 10 and 12): a rotation that is waiting for the lock of a family that a password reset
 * revokes is rejected as revoked, consumes no token and inserts no successor. With READ COMMITTED the
 * rotation looks the token up without a lock, so by the time it gets the lock of the family the reset
 * may have revoked it; the state it decides on must be the one it reads <b>after</b> taking the lock,
 * never the one it could have read before. The rotation never locks the account, and the reset locks
 * account and then families, so the two cannot form a cycle.
 *
 * <p>No clock decides any order. The reset is held with a {@link CountDownLatch} at the exact point
 * under test, with the family revoked and not committed; the rotation is started, and the test goes
 * on when the database reports a backend waiting for a row lock on the family, which is a condition
 * with a bound, not a pause.
 */
class RefreshRotationConcurrencyIT extends RotationIntegrationTest {

    private static final String NEW_PASSWORD = "Lempira y maíz 2026";
    private static final long LIMIT_MILLIS = 20_000;
    private static final SecondFactorProof NONE = new SecondFactorProof.None();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aRotationWaitingForTheLockOfAFamilyTheResetRevokesIsRejectedAndConsumesNothing()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        PlainPasswordResetToken resetToken = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JooqAccountSessionRevoker real = new JooqAccountSessionRevoker(dsl);
        AccountSessionRevoker holdingBeforeCommit = (institution, account, reason, now) -> {
            int count = real.revokeAllLive(institution, account, reason, now);
            revoked.countDown();
            awaitRelease(release);
            return count;
        };

        ResetPasswordDecision decision;
        RefreshOutcome outcome;
        boolean rotationWaited;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResetPasswordDecision> resetting = pool.submit(() -> resetUseCase(
                    "2026-10-15T15:05:00Z", new JooqAuditLogWriter(dsl),
                    new JooqPasswordResetTokenRepository(dsl), institutionId, holdingBeforeCommit)
                    .execute(context(), new ResetPasswordCommand(resetToken.value(), NEW_PASSWORD,
                            NONE)));
            assertThat(revoked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<RefreshOutcome> rotating = pool.submit(() -> rotate("2026-10-15T15:06:00Z",
                    session.refreshToken().value(), AGENT_ONE));
            rotationWaited = holdsWithin(LIMIT_MILLIS, () -> rotating.isDone()
                    || someBackendWaitsForALockOn("identity_refresh_token_family"))
                    && !rotating.isDone();
            release.countDown();
            decision = resetting.get(60, TimeUnit.SECONDS);
            outcome = rotating.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        assertThat(rotationWaited)
                .as("the rotation waited for the family lock the reset held").isTrue();
        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(outcome).isEqualTo(new RefreshOutcome.Rejected(RefreshRejection.FAMILY_REVOKED));
        List<Record> tokens = tokensOf(session.sessionId());
        assertThat(tokens).as("no successor was inserted").hasSize(1);
        assertThat(tokens.get(0).get("consumed_at")).as("the token was not consumed").isNull();
        assertThat(tokens.get(0).get("replaced_by")).isNull();
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("password_change");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:05:00Z"));
        assertThat(instantOf(family, "last_used_at"))
                .as("a rejected rotation does not count as a use")
                .isEqualTo(Instant.parse("2026-10-15T15:00:00Z"));
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
        assertThat(auditRows("identity.session.refresh_rejected")).singleElement().satisfies(row -> {
            assertThat(row.entityId()).isEqualTo(session.sessionId().toString());
            assertThat(JSON.readTree(row.afterValue()).get("reason").asString())
                    .isEqualTo("family_revoked");
        });
    }

    /**
     * Scenario I143, case (b): the same token presented from two devices at the same instant. The
     * first transaction owns the lock of the family and is held right after taking it; the second is
     * started and observed waiting. When the first goes on it rotates, and the second, reading the
     * consumed token under the lock, finds a token that was consumed by another agent: a reuse.
     *
     * <p><b>What it proves.</b> Held right after the lock and before the re-read of the token, the
     * outcome (one fresh rotation, one reuse, one successor, a revoked family) is the same in either
     * order, and with the lock removed only the roles swap (observed, 2026-10-16): the "waited" assertion
     * and the roles tie the test to the lock, the final state does not. The test that reaches an outcome
     * assertion when the lock is removed is the next one, which holds the first transaction after its
     * re-read.
     */
    @Test
    void theSameTokenFromTwoDevicesIsOneFreshRotationAndOneReuseWithExactlyOneSuccessor()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:05:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, RefreshOutcome> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(token, AGENT_ONE)),
                () -> rotate("2026-10-15T15:05:00Z", token, AGENT_TWO));

        assertThat(race.waiterWaited())
                .as("the second presentation waited for the family lock the first held").isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        assertThat(((Rotated) race.holder()).kind()).isEqualTo(RotationKind.FRESH);
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:05:00Z"));
        List<Record> tokens = tokensOf(session.sessionId());
        assertThat(tokens).as("exactly one successor of the presented token").hasSize(2);
        assertThat(tokens.get(0).get("replaced_by", UUID.class))
                .isEqualTo(tokens.get(1).get("id", UUID.class));
        assertThat(liveTokenCount()).as("no token of the family is live").isZero();
        assertThat(auditRows("identity.session.refreshed")).hasSize(1);
        assertThat(auditRows("identity.session.reuse_detected")).hasSize(1);
        assertThat(auditRows("identity.session.refresh_rejected")).isEmpty();
    }

    /**
     * Case (b) held one step later, where the family lock is the only thing in the way: the first
     * transaction has already locked the family and read the token again, and is held before it
     * decides. A second one that was not kept out by the lock would read the same open token, rotate it
     * and commit; the first would then act on a state that is no longer true and meet the conditional
     * update of {@code consume} (an exception, never a reuse). With the lock, the second waits, and the
     * outcome assertions below (both transactions finish, one fresh rotation and one reuse, one
     * successor, a revoked family) hold. Removing the lock turns this test red on the exception.
     */
    @Test
    void whenTheFirstTransactionHasAlreadyReadTheTokenTheSecondOneStillWaitsAndTheOutcomeIsTheSame()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch reread = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:05:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new JooqRefreshTokenFamilyRepository(dsl),
                new HoldingTokensAfterReread(new JooqRefreshTokenRepository(dsl), reread, release));

        Race<RefreshOutcome, RefreshOutcome> race = race(reread, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(token, AGENT_ONE)),
                () -> rotate("2026-10-15T15:05:00Z", token, AGENT_TWO));

        assertThat(race.waiterWaited())
                .as("the second presentation waited for the family lock the first held").isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(tokensOf(session.sessionId())).as("exactly one successor").hasSize(2);
        assertThat(familyRow(session.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
        assertThat(liveTokenCount()).isZero();
    }

    /**
     * Case (c), the order the closing condition of 4.1 did not cover: the rotation owns the lock and
     * the reset arrives second. The reset waits, the rotation completes and inserts its successor, and
     * the reset then revokes the family, so the successor is born into a family that ends revoked.
     *
     * <p><b>What it proves.</b> The final-state assertions hold in any interleaving (a revocation is
     * final and liveness is derived from the family); with the family lock removed they all stay green
     * (observed, 2026-10-16). Only the "waited" assertion ties this test to the lock, so it fixes the
     * order of the serialization, not the final state. The orders where the lock changes the outcome are
     * the reset holding first (above) and the reuse holding first (I108, second order).
     */
    @Test
    void aResetThatWaitsForARotationRevokesTheFamilyAndLeavesNoLiveToken() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        PlainPasswordResetToken resetToken = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:05:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, ResetPasswordDecision> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(
                        session.refreshToken().value(), AGENT_ONE)),
                () -> resetAt(resetToken.value(), NEW_PASSWORD, NONE, "2026-10-15T15:06:00Z"));

        assertThat(race.waiterWaited())
                .as("the reset waited for the family lock the rotation held").isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        Rotated rotated = (Rotated) race.holder();
        assertThat(race.waiter().outcome()).isInstanceOf(Completed.class);
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("password_change");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:06:00Z"));
        assertThat(tokensOf(session.sessionId())).as("the rotation did complete").hasSize(2);
        assertThat(liveTokenCount()).as("the new successor lives in a revoked family").isZero();
        assertThat(rotate("2026-10-15T15:07:00Z", rotated.session().refreshToken().value(), AGENT_ONE))
                .isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
    }

    /**
     * Scenario I108, the rotation owning the lock: the consumed token is presented while the open
     * successor is being rotated. The rotation completes first; the reuse then revokes the family and
     * the successor of that rotation is born dead.
     *
     * <p><b>What it proves.</b> The final-state assertions hold in any interleaving, because a revocation
     * is final (V8 trigger) and a token is live only while its family is not revoked; with the family
     * lock removed this test stays green on every outcome assertion (observed, 2026-10-16). Only the
     * "waited" assertion ties it to the lock, so what it fixes is the order of the serialization, not
     * the final state.
     */
    @Test
    void aReuseThatWaitsForARotationOfTheSuccessorRevokesTheFamilyAndLeavesNoLiveToken()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T14:00:00Z", "2026-10-15T15:15:00Z",
                "2026-10-16T02:00:00Z", null, null);
        ConsumedSeed seed = seedConsumedToken(family, "2026-10-15T14:00:00Z", "2026-10-15T22:00:00Z",
                "2026-10-15T15:00:00Z", AGENT_ONE, false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:20:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, RefreshOutcome> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(
                        seed.successor().value(), AGENT_ONE)),
                () -> rotate("2026-10-15T15:20:00Z", seed.consumed().value(), AGENT_ONE));

        assertThat(race.waiterWaited()).isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(tokensOf(family)).hasSize(3);
        assertThat(liveTokenCount()).isZero();
    }

    /**
     * Scenario I108, the reuse owning the lock: the rotation of the open successor waits, and when
     * the reuse commits it reads the family as revoked, consumes nothing and inserts no successor.
     */
    @Test
    void aRotationThatWaitsForAReuseOfTheConsumedTokenIsRejectedAsRevokedAndConsumesNothing()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T14:00:00Z", "2026-10-15T15:15:00Z",
                "2026-10-16T02:00:00Z", null, null);
        ConsumedSeed seed = seedConsumedToken(family, "2026-10-15T14:00:00Z", "2026-10-15T22:00:00Z",
                "2026-10-15T15:00:00Z", AGENT_ONE, false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:20:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, RefreshOutcome> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(
                        seed.consumed().value(), AGENT_ONE)),
                () -> rotate("2026-10-15T15:20:00Z", seed.successor().value(), AGENT_ONE));

        assertThat(race.waiterWaited()).isTrue();
        assertThat(race.holder()).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        List<Record> tokens = tokensOf(family);
        assertThat(tokens).as("the rotation inserted no successor").hasSize(2);
        assertThat(tokens.get(1).get("consumed_at")).as("and consumed nothing").isNull();
        assertThat(liveTokenCount()).isZero();
    }

    /** What two transactions leave: both results and whether the second really waited for the first. */
    private record Race<A, B>(A holder, B waiter, boolean waiterWaited) {
    }

    /**
     * Runs {@code holder}, which must count {@code locked} down once it owns the lock of the family
     * and then wait for {@code release}; starts {@code waiter} once that happened; goes on when the
     * database reports a backend waiting for a row lock on the family (a condition with a bound, never
     * a pause); and releases the holder.
     */
    private <A, B> Race<A, B> race(CountDownLatch locked, CountDownLatch release, Callable<A> holder,
            Callable<B> waiter) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<A> first = pool.submit(holder);
                assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
                Future<B> second = pool.submit(waiter);
                boolean waited = holdsWithin(LIMIT_MILLIS, () -> second.isDone()
                        || someBackendWaitsForALockOn("identity_refresh_token_family"))
                        && !second.isDone();
                release.countDown();
                return new Race<>(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS),
                        waited);
            } finally {
                release.countDown();
            }
        }
    }
}
