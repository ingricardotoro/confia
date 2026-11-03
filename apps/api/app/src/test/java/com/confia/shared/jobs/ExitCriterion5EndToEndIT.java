package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exit criterion 5 of phase 0, both halves in one run against a real PostgreSQL (scenario BJ37 of
 * background-jobs-with-db-scheduler; the parts are proved on their own by {@code JobSchedulingAtomicityIT}
 * and {@code OnlyWorkerExecutesIT}): the scheduling side puts one task in a rolled-back transaction and
 * one in a committed one; the first never exists and never runs, and the second exists and is executed
 * once, by the worker and by nobody before it.
 *
 * <p>As in {@code OnlyWorkerExecutesIT}, the scheduling side is the real client over the test pool: the
 * administrative process holds no executor at all, which {@code ProcessBeanIsolationTest} asserts on the
 * real administrative context.
 */
class ExitCriterion5EndToEndIT extends CommittingPostgresIntegrationTest {

    /** Executions by instance, with the name of the thread that ran each one. */
    static final Map<String, String> EXECUTED_ON = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    /** The message of the failure thrown after scheduling: the rollback must come from there and from nowhere else. */
    private static final String AFTER_SCHEDULING = "the business operation failed after scheduling";

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition probeJob() {
            return new OneTimeJob<>(TYPE, (payload, context) -> {
                EXECUTED_ON.put(context.instance().value(), Thread.currentThread().getName());
                EXECUTIONS.computeIfAbsent(context.instance().value(), k -> new AtomicInteger()).incrementAndGet();
            });
        }
    }

    private static String pickedByOf(JobInstanceId instance) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select picked, picked_by from scheduled_tasks where task_instance = ?")) {
            statement.setString(1, instance.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBoolean(1) + "/" + result.getString(2) : "gone";
            }
        }
    }

    @Test
    void aRolledBackTaskNeverExistsAndACommittedOneIsExecutedOnlyByTheWorker() throws Exception {
        UUID account = UUID.randomUUID();
        UUID rolledBackRequest = UUID.randomUUID();
        UUID committedRequest = UUID.randomUUID();
        JobInstanceId rolledBack = SchedulingFixtures.instanceOf(INSTITUTION, account, rolledBackRequest);
        JobInstanceId committed = SchedulingFixtures.instanceOf(INSTITUTION, account, committedRequest);
        JobScheduler scheduler = new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()),
                new TracedJobSerializer());
        Instant due = Instant.now().minusSeconds(5);

        assertThatThrownBy(() -> transactionRunner().execute(
                SchedulingFixtures.contextOf(INSTITUTION, rolledBackRequest), () -> {
                    scheduler.schedule(TYPE, rolledBack, new Probe(account),
                            SchedulingFixtures.metaOf(INSTITUTION, rolledBackRequest), due);
                    throw new IllegalStateException(AFTER_SCHEDULING);
                })).isInstanceOf(IllegalStateException.class).hasMessage(AFTER_SCHEDULING);
        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, committedRequest), () ->
                scheduler.schedule(TYPE, committed, new Probe(account),
                        SchedulingFixtures.metaOf(INSTITUTION, committedRequest), due));

        assertThat(SchedulingFixtures.tasksOf(rolledBack)).as("the rolled-back task does not exist").isZero();
        assertThat(SchedulingFixtures.tasksOf(committed)).as("the committed task exists").isOne();

        // Only the scheduling side is up: the due task is not taken over several 1 s polls.
        Eventually.throughout(Duration.ofSeconds(5), () -> {
            assertThat(pickedByOf(committed)).as("not picked, row still there").isEqualTo("false/null");
            assertThat(EXECUTIONS).doesNotContainKeys(committed.value(), rolledBack.value());
        });

        try (ConfigurableApplicationContext worker = WorkerHarness.start(Fixtures.class, INSTITUTION)) {
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(SchedulingFixtures.tasksOf(committed)).as("completed and removed").isZero());
            assertThat(EXECUTIONS.get(committed.value()).get()).as("executed exactly once").isEqualTo(1);
            assertThat(EXECUTED_ON.get(committed.value())).as("on a thread of the worker's executor")
                    .startsWith("db-scheduler");
            assertThat(EXECUTIONS).as("the rolled-back task never ran").doesNotContainKey(rolledBack.value());
            assertThat(SchedulingFixtures.tasksOf(rolledBack)).as("and never appeared").isZero();
        }
    }
}
