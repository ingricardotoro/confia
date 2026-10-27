package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A dead execution is revived well inside the 900 s goal of the {@code QueueStalled} alert, and a
 * long, healthy one is not (background-jobs-with-db-scheduler design.md decisions 1 and 4; scenarios
 * BJ25 and BJ26). The heartbeat is one second in the tests, so six missed beats are about six
 * seconds; the production values are one minute and six, about six minutes.
 */
class DeadExecutionRevivalIT extends WorkerJobIT {

    static final JobType<Probe> DEAD = fastType("identity.dead-probe", Probe.class, 2);
    static final JobType<Probe> LONG = fastType("identity.long-probe", Probe.class, 2);
    static final ConcurrentHashMap<String, AtomicInteger> RUNS = new ConcurrentHashMap<>();
    static volatile CountDownLatch longRunning;

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition dead() {
            return new OneTimeJob<>(DEAD, (payload, context) ->
                    RUNS.computeIfAbsent(context.instance().value(), k -> new AtomicInteger()).incrementAndGet());
        }

        @Bean
        JobDefinition longJob() {
            return new OneTimeJob<>(LONG, (payload, context) -> {
                RUNS.computeIfAbsent(context.instance().value(), k -> new AtomicInteger()).incrementAndGet();
                try {
                    longRunning.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /** Marks the row as taken by a worker that stopped beating a while ago. */
    private static void killTheWorkerThatHeldIt(JobInstanceId instance) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "update scheduled_tasks set picked = true, picked_by = 'dead-worker', "
                                + "last_heartbeat = now() - interval '10 minutes', version = version + 1 "
                                + "where task_instance = ?")) {
            statement.setString(1, instance.value());
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void anExecutionHeldByADeadWorkerIsRevivedAndRunsOnceInFarLessThanNineHundredSeconds() throws Exception {
        JobInstanceId instance = scheduleProbe(DEAD);
        killTheWorkerThatHeldIt(instance);
        long started = System.nanoTime();

        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            Eventually.within(Duration.ofSeconds(120), () -> assertThat(rowOf(instance)).isNull());
        }

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(900));
        assertThat(RUNS.get(instance.value()).get()).as("revived and executed once").isEqualTo(1);
    }

    @Test
    void aLongHealthyExecutionIsNotRevivedByAnotherWorker() throws Exception {
        longRunning = new CountDownLatch(1);
        JobInstanceId instance = scheduleProbe(LONG);

        try (ConfigurableApplicationContext first = WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION);
                ConfigurableApplicationContext second =
                        WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(RUNS).containsKey(instance.value()));
            // Longer than the six missed beats: a sick execution would have been revived by now.
            Eventually.throughout(Duration.ofSeconds(12), () -> {
                assertThat(RUNS.get(instance.value()).get()).isEqualTo(1);
                assertThat(rowOf(instance).picked()).isTrue();
            });
            longRunning.countDown();
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(rowOf(instance)).isNull());
        } finally {
            longRunning.countDown();
        }

        assertThat(RUNS.get(instance.value()).get()).as("one execution in total").isEqualTo(1);
    }
}
