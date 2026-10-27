package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Scenario BJ04: when {@code TransactionRunner} retries the transaction after a serialization
 * failure ({@code 40001}) or a deadlock ({@code 40P01}), the task exists once. The first attempt
 * schedules and then fails; the second attempt schedules the same instance again. That the second
 * answer is {@code SCHEDULED} and not {@code ALREADY_SCHEDULED} proves that the first attempt's
 * insert was rolled back with the rest of its transaction, instead of surviving it.
 */
class JobSchedulingRetriedTransactionIT extends CommittingPostgresIntegrationTest {

    private void scheduleThenFailOnTheFirstAttempt(String sqlState) {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()),
                new TracedJobSerializer());
        List<ScheduleOutcome> outcomes = new ArrayList<>();

        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () -> {
            outcomes.add(scheduler.schedule(TYPE, instance, new Probe(account),
                    SchedulingFixtures.metaOf(INSTITUTION, request), Instant.parse("2026-10-25T12:00:00Z")));
            if (outcomes.size() == 1) {
                dsl.execute("do $$ begin raise exception using errcode = '" + sqlState + "'; end $$;");
            }
            return null;
        });

        assertThat(outcomes).as("two attempts, and the second one found nothing left by the first")
                .containsExactly(ScheduleOutcome.SCHEDULED, ScheduleOutcome.SCHEDULED);
        assertThat(SchedulingFixtures.tasksOf(instance)).as("one task").isEqualTo(1);
    }

    @Test
    void aSerializationFailureRetryLeavesOneTask() {
        scheduleThenFailOnTheFirstAttempt("40001");
    }

    @Test
    void aDeadlockRetryLeavesOneTask() {
        scheduleThenFailOnTheFirstAttempt("40P01");
    }
}
