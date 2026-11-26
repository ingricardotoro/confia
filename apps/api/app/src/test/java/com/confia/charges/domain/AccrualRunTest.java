package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The state machine of an accrual run and the map of posting accounts. */
class AccrualRunTest {

    private static final Instant NOW = Instant.parse("2027-02-01T12:00:00Z");
    private static final String HASH = "a".repeat(64);

    private static AccrualRun queued() {
        return AccrualRun.queue(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2027-02-01"), "key-1", HASH, null,
                UUID.randomUUID(), NOW);
    }

    @Test
    void aRunIsBornQueuedStartsAndCompletes() {
        AccrualRun run = queued().start(NOW).complete(new AccrualCounters(3, 3, 0, 0), NOW);

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.startedAt()).isEqualTo(NOW);
        assertThat(run.finishedAt()).isEqualTo(NOW);
    }

    @Test
    void aRunWithAnErrorCompletesWithErrors() {
        assertThat(queued().start(NOW).complete(new AccrualCounters(3, 2, 0, 1), NOW).status())
                .isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void startingARunningRunIsTheResumptionAfterACrash() {
        AccrualRun running = queued().start(NOW);

        assertThat(running.start(NOW.plusSeconds(60))).isSameAs(running);
    }

    @Test
    void aQueuedRunDoesNotCompleteWithoutHavingRun() {
        assertThatThrownBy(() -> queued().complete(AccrualCounters.ZERO, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFinishedRunNeverChanges() {
        AccrualRun completed = queued().start(NOW).complete(AccrualCounters.ZERO, NOW);
        AccrualRun rejected = queued().reject(AccrualRejection.PLAN_NOT_ACTIVE, NOW);
        AccrualRun failed = queued().start(NOW).fail(AccrualRejection.RETRIES_EXHAUSTED, NOW);

        for (AccrualRun finished : new AccrualRun[] {completed, rejected, failed}) {
            assertThat(finished.status().isTerminal()).isTrue();
            assertThatThrownBy(() -> finished.start(NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finished.reject(AccrualRejection.PERIOD_IN_FUTURE, NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finished.fail(AccrualRejection.RETRIES_EXHAUSTED, NOW))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> finished.complete(AccrualCounters.ZERO, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(rejected.failureCode()).isEqualTo("plan-not-active");
        assertThat(failed.failureCode()).isEqualTo("retries-exhausted");
    }

    @Test
    void theStateMachineHasNoWayBackToQueued() {
        for (RunStatus status : RunStatus.values()) {
            assertThat(status.canMoveTo(RunStatus.QUEUED)).as(status.name()).isFalse();
        }
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.COMPLETED)).isFalse();
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.COMPLETED_WITH_ERRORS)).isFalse();
    }

    @Test
    void aRunOfAPeriodThatIsNotTheFirstOfAMonthIsRefused() {
        assertThatThrownBy(() -> AccrualRun.queue(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2027-02-02"),
                "key-1", HASH, null, UUID.randomUUID(), NOW)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void thePostingAccountMapIsCompleteOnlyWithReceivableTaxAndARevenueForEveryCategory() {
        UUID receivable = UUID.randomUUID();
        UUID tax = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID transport = UUID.randomUUID();
        PostingAccountMap map = new PostingAccountMap(java.util.List.of(
                new PostingAccountMap.Entry(PostingPurpose.RECEIVABLE, null, receivable),
                new PostingAccountMap.Entry(PostingPurpose.TAX_PAYABLE, null, tax),
                new PostingAccountMap.Entry(PostingPurpose.REVENUE, "TRANSPORT", transport)));

        assertThat(map.revenueFor("TRANSPORT")).contains(transport);
        assertThat(map.revenueFor("TUITION")).isEmpty();
        assertThat(map.completeFor(java.util.List.of("TUITION"), true)).isFalse();

        PostingAccountMap withDefault = new PostingAccountMap(java.util.List.of(
                new PostingAccountMap.Entry(PostingPurpose.RECEIVABLE, null, receivable),
                new PostingAccountMap.Entry(PostingPurpose.TAX_PAYABLE, null, tax),
                new PostingAccountMap.Entry(PostingPurpose.REVENUE, null, revenue),
                new PostingAccountMap.Entry(PostingPurpose.REVENUE, "TRANSPORT", transport)));

        assertThat(withDefault.revenueFor("TUITION")).contains(revenue);
        assertThat(withDefault.revenueFor("TRANSPORT")).contains(transport);
        assertThat(withDefault.completeFor(java.util.List.of("TUITION", "TRANSPORT"), true)).isTrue();
        assertThat(new PostingAccountMap(java.util.List.of()).completeFor(java.util.List.of(), false)).isFalse();
        assertThatThrownBy(() -> new PostingAccountMap.Entry(PostingPurpose.RECEIVABLE, "TUITION", receivable))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void thePostingAccountMapNeedsTheBenefitsAccountOnlyWhenABenefitMayApply() {
        UUID receivable = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID benefits = UUID.randomUUID();
        PostingAccountMap without = new PostingAccountMap(java.util.List.of(
                new PostingAccountMap.Entry(PostingPurpose.RECEIVABLE, null, receivable),
                new PostingAccountMap.Entry(PostingPurpose.REVENUE, null, revenue)));
        PostingAccountMap with = new PostingAccountMap(java.util.List.of(
                new PostingAccountMap.Entry(PostingPurpose.RECEIVABLE, null, receivable),
                new PostingAccountMap.Entry(PostingPurpose.REVENUE, null, revenue),
                new PostingAccountMap.Entry(PostingPurpose.BENEFITS, null, benefits)));

        assertThat(without.benefits()).isEmpty();
        assertThat(without.completeFor(java.util.List.of("TUITION"), false)).isTrue();
        assertThat(without.completeFor(java.util.List.of("TUITION"), false, true)).isFalse();
        assertThat(with.benefits()).contains(benefits);
        assertThat(with.completeFor(java.util.List.of("TUITION"), false, true)).isTrue();
        assertThatThrownBy(() -> new PostingAccountMap.Entry(PostingPurpose.BENEFITS, "TUITION", benefits))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
