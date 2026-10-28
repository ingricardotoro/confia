package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.jobs.TracedJob;
import com.confia.shared.jobs.WorkerHarness;
import com.confia.shared.jobs.infrastructure.JobExecutionFailedException;
import com.confia.shared.jobs.infrastructure.JobRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The issuance with the real sender of the worker, which fails explicitly (owner decision P4;
 * scenarios I265, I266, I267, I269 and I277 of background-jobs-with-db-scheduler): every attempt fails before
 * a token is issued, so the failed attempts leave no orphan token, no audit entry and no use of the
 * hourly limit; the check runs before the transaction opens; and the failure names neither the
 * token, the link nor the address.
 */
class UnavailableSenderLeavesNoTokenIT extends PasswordResetIssuanceFixtures {

    /** The worker harness needs a fixtures class; this one registers nothing. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

    /** Scenarios I265 and I266 (the retry policy the five attempts follow). */
    @Test
    void theJobTypeAllowsFiveAttemptsWithAGrowingWait() {
        var retry = PasswordResetIssuanceJobs.TYPE.retry();

        assertThat(retry.maxAttempts()).isEqualTo(5);
        assertThat(List.of(1, 2, 3, 4).stream().map(retry::delayAfterFailure).map(Optional::orElseThrow))
                .containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120),
                        Duration.ofSeconds(240));
        assertThat(retry.delayAfterFailure(5)).as("spent: the execution is parked").isEmpty();
    }

    /**
     * Scenarios I265, I266, I267 and I277 with the real worker, for an account that is below its
     * hourly limit and for one that is at it: five attempts each, all fail with the sender's
     * failure, so no attempt ends as completed or as skipped by the limit; nothing is issued or
     * audited, and the live token the account already had is untouched.
     */
    @Test
    void fiveFailedAttemptsLeaveNoTokenNoIssuedEntryAndTheLiveTokenUntouched() {
        UUID fresh = seedAccount(INSTITUTION_A, ANA);
        UUID atLimit = seedAccount(INSTITUTION_A, "luis.mejia@colegio.edu.hn");
        // Three issuances inside the last hour: the account is at its limit, so a handler that got
        // as far as the limit would end as "skipped", not as a failure.
        for (long minutesAgo : new long[] {30, 20, 10}) {
            clock.set(Instant.now().minusSeconds(minutesAgo * 60).toString());
            run(INSTITUTION_A, atLimit);
        }
        PlainPasswordResetToken live = lastSent();
        List<String> auditBefore = auditActions(INSTITUTION_A);
        assertThat(tokenCount(INSTITUTION_A, atLimit)).isEqualTo(3);

        List<JobExecutionFailedException> failures = new ArrayList<>();
        try (ConfigurableApplicationContext worker = WorkerHarness.start(NoFixtures.class, INSTITUTION_A,
                "--confia.jobs.executor.autostart=false")) {
            JobRunner runner = worker.getBean(JobRunner.class);
            OneTimeJob<PasswordResetIssuanceJob> job = issuanceJobOf(worker);
            for (UUID account : List.of(fresh, atLimit)) {
                UUID request = UUID.randomUUID();
                JobMeta meta = new JobMeta(request, request, null, ActorKind.STAFF, INSTITUTION_A, null);
                JobInstanceId instance = JobInstanceId.derive(job.name(), INSTITUTION_A, account, request);
                for (int attempt = 1; attempt <= 5; attempt++) {
                    int number = attempt;
                    assertThatThrownBy(() -> runner.run(job, instance.value(),
                            new TracedJob<>(new PasswordResetIssuanceJob(account), meta), number))
                            .as("attempt %d fails, it neither completes nor is skipped", number)
                            .isInstanceOfSatisfying(JobExecutionFailedException.class, failures::add);
                }
            }
        }

        assertThat(failures).hasSize(10).allSatisfy(failure -> {
            assertThat(failure.originalClass()).isEqualTo(PasswordResetDeliveryUnavailableException.class.getName());
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getMessage()).doesNotContain("ana.martinez").doesNotContain("luis.mejia")
                    .doesNotContain(live.value()).doesNotContain(fresh.toString())
                    .doesNotContain(atLimit.toString()).doesNotContain("http");
        });
        assertThat(tokenCount(INSTITUTION_A, fresh)).as("no orphan token for the fresh account").isZero();
        assertThat(tokenCount(INSTITUTION_A, atLimit)).as("no new token at the limit").isEqualTo(3);
        assertThat(openTokenCount(INSTITUTION_A, atLimit)).isEqualTo(1);
        assertThat(isOpen(INSTITUTION_A, live)).as("the live token is not superseded").isTrue();
        assertThat(auditActions(INSTITUTION_A)).as("no issued and no skipped entry for the attempts")
                .isEqualTo(auditBefore);
    }

    /**
     * Scenarios I269 and I277: the availability check runs outside any transaction, before the one
     * of the issuance, and the delivery runs outside it too, after the commit.
     */
    @Test
    void theAvailabilityCheckRunsBeforeTheTransactionOpensAndTheDeliveryAfterItCommits() {
        UUID account = seedAccount(INSTITUTION_A, ANA);
        List<String> order = new ArrayList<>();
        PasswordResetLinkSender observing = new PasswordResetLinkSender() {
            @Override
            public void ensureAvailable() {
                order.add("ensureAvailable inTransaction=" + TransactionSynchronizationManager.isActualTransactionActive());
            }

            @Override
            public void send(InstitutionId institutionId, StaffAccountId accountId, PlainPasswordResetToken token) {
                order.add("send inTransaction=" + TransactionSynchronizationManager.isActualTransactionActive());
            }
        };

        new PasswordResetIssuanceJobHandler(new IssuePasswordResetToken(transactionRunner(),
                new com.confia.identity.infrastructure.JooqStaffAccountRepository(dsl),
                new com.confia.identity.infrastructure.JooqPasswordResetTokenRepository(dsl), observing,
                new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl), new java.security.SecureRandom(),
                clock)).handle(new PasswordResetIssuanceJob(account), contextOf(INSTITUTION_A, account, 1));

        assertThat(order).containsExactly("ensureAvailable inTransaction=false", "send inTransaction=false");
        assertThat(openTokenCount(INSTITUTION_A, account)).isEqualTo(1);
    }

    /** Scenario I277: a sender that fails its check stops the attempt before the transaction, every time. */
    @Test
    void aSenderThatFailsItsCheckStopsEveryAttemptBeforeTheTransactionOpens() {
        UUID account = seedAccount(INSTITUTION_A, ANA);
        List<Boolean> transactionOpenAtCheck = new ArrayList<>();
        PasswordResetLinkSender failing = new PasswordResetLinkSender() {
            @Override
            public void ensureAvailable() {
                transactionOpenAtCheck.add(TransactionSynchronizationManager.isActualTransactionActive());
                throw new PasswordResetDeliveryUnavailableException();
            }

            @Override
            public void send(InstitutionId institutionId, StaffAccountId accountId, PlainPasswordResetToken token) {
                throw new PasswordResetDeliveryUnavailableException();
            }
        };
        var handler = new PasswordResetIssuanceJobHandler(new IssuePasswordResetToken(transactionRunner(),
                new com.confia.identity.infrastructure.JooqStaffAccountRepository(dsl),
                new com.confia.identity.infrastructure.JooqPasswordResetTokenRepository(dsl), failing,
                new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl), new java.security.SecureRandom(),
                clock));

        for (int attempt = 1; attempt <= 5; attempt++) {
            int number = attempt;
            assertThatThrownBy(() -> handler.handle(new PasswordResetIssuanceJob(account),
                    contextOf(INSTITUTION_A, account, number)))
                    .isInstanceOf(PasswordResetDeliveryUnavailableException.class);
        }

        assertThat(transactionOpenAtCheck).containsExactly(false, false, false, false, false);
        assertThat(tokenCountOfInstitution(INSTITUTION_A)).isZero();
        assertThat(auditActions(INSTITUTION_A)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static OneTimeJob<PasswordResetIssuanceJob> issuanceJobOf(ConfigurableApplicationContext worker) {
        return worker.getBeansOfType(JobDefinition.class).values().stream()
                .filter(definition -> definition.name().equals(PasswordResetIssuanceJobs.TYPE.name()))
                .map(definition -> (OneTimeJob<PasswordResetIssuanceJob>) definition)
                .findFirst().orElseThrow(() -> new AssertionError("the worker registers the issuance job"));
    }
}
