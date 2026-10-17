package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.List;
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
}
