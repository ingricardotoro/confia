package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Exit criterion 5, second half (background-jobs-with-db-scheduler design.md decisions 1 and 12;
 * scenarios BJ06 and BJ07): a task scheduled from the administrative side is executed by the worker
 * and by nobody else. With only the scheduling side running, the task is never taken over several
 * polls of the executor; once the worker starts it is taken, with {@code picked_by}, and its effect
 * exists exactly once.
 *
 * <p>The scheduling side here is the real {@link DbSchedulerJobScheduler} over the test pool; the
 * administrative process holds no {@code Scheduler} (asserted by {@code ProcessBeanIsolationTest}).
 */
class OnlyWorkerExecutesIT extends CommittingPostgresIntegrationTest {

    static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition probeJob() {
            return new OneTimeJob<>(TYPE, (payload, context) ->
                    EXECUTIONS.computeIfAbsent(context.instance().value(), k -> new AtomicInteger())
                            .incrementAndGet());
        }
    }

    private void schedule(JobInstanceId instance, UUID account, UUID request) {
        JobScheduler scheduler = new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()),
                new TracedJobSerializer());
        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () ->
                scheduler.schedule(TYPE, instance, new Probe(account), SchedulingFixtures.metaOf(INSTITUTION, request),
                        Instant.now().minusSeconds(5)));
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
    void aDueTaskIsNeverTakenWithoutTheWorkerAndIsExecutedOnceWhenTheWorkerStarts() throws Exception {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        schedule(instance, account, request);

        // Only the scheduling side is up: the due task stays untouched across several 1 s polls of
        // any executor there might be. Observed through the row and the effect, not through a sleep.
        Eventually.throughout(Duration.ofSeconds(5), () -> {
                    assertThat(pickedByOf(instance)).as("not picked, row still there").isEqualTo("false/null");
                    assertThat(EXECUTIONS).doesNotContainKey(instance.value());
                });

        try (ConfigurableApplicationContext worker = WorkerHarness.start(Fixtures.class, INSTITUTION)) {
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(SchedulingFixtures.tasksOf(instance)).as("completed and removed").isZero());
            assertThat(EXECUTIONS.get(instance.value()).get()).as("executed exactly once").isEqualTo(1);
        }
    }

    @Test
    void theWorkerRecordsItselfInPickedByWhileItRunsTheTask() throws Exception {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        BlockingFixtures.inside = inside;
        BlockingFixtures.release = release;
        schedule(instance, account, request);

        try (ConfigurableApplicationContext worker = WorkerHarness.start(BlockingFixtures.class, INSTITUTION)) {
            assertThat(inside.await(60, java.util.concurrent.TimeUnit.SECONDS)).as("the handler was entered").isTrue();
            assertThat(pickedByOf(instance)).startsWith("true/").doesNotContain("null");
            release.countDown();
            Eventually.within(Duration.ofSeconds(60), () ->
                    assertThat(SchedulingFixtures.tasksOf(instance)).isZero());
        } finally {
            release.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BlockingFixtures {
        static volatile java.util.concurrent.CountDownLatch inside;
        static volatile java.util.concurrent.CountDownLatch release;

        @Bean
        JobDefinition probeJob() {
            return new OneTimeJob<>(TYPE, (payload, context) -> {
                inside.countDown();
                try {
                    release.await(60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
