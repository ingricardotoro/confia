package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.ParkedExecutions;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bounded retries and the parked state (background-jobs-with-db-scheduler design.md decision 9;
 * scenarios BJ21, BJ22, BJ23, BJ58 and BJ59): a job that always fails is attempted exactly as many
 * times as its type allows, is then parked at {@code 9999-01-01T00:00:00Z} with its failure count and
 * its last failure, and is never deleted; a transient failure is retried and succeeds. The negative
 * control runs the library's own {@code maxRetries().thenRemove()} policy, which deletes the row.
 */
class FailedExecutionIsParkedIT extends WorkerJobIT {

    static final JobType<Probe> ALWAYS_FAILS = fastType("identity.always-fails", Probe.class, 3);
    static final JobType<Probe> FAILS_ONCE = fastType("identity.fails-once", Probe.class, 3);
    static final AtomicInteger ATTEMPTS = new AtomicInteger();
    static final ConcurrentHashMap<String, AtomicInteger> SUCCESSES = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition alwaysFails() {
            return new OneTimeJob<>(ALWAYS_FAILS, (payload, context) -> {
                ATTEMPTS.incrementAndGet();
                throw new IllegalStateException("always fails");
            });
        }

        @Bean
        JobDefinition failsOnce() {
            return new OneTimeJob<>(FAILS_ONCE, (payload, context) -> {
                if (context.attempt() == 1) {
                    throw new IllegalStateException("transient");
                }
                SUCCESSES.computeIfAbsent(context.instance().value(), k -> new AtomicInteger()).incrementAndGet();
            });
        }
    }

    @Test
    void aJobThatAlwaysFailsIsAttemptedThreeTimesAndParkedNeverDeleted() {
        ATTEMPTS.set(0);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        JobInstanceId instance;
        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            // After the start: Spring Boot re-initializes Logback while the context is prepared and
            // would detach an appender added before it.
            root.addAppender(appender);
            instance = scheduleProbe(ALWAYS_FAILS);
            Eventually.within(Duration.ofSeconds(90), () -> {
                Row row = rowOf(instance);
                assertThat(row).as("the row is never deleted").isNotNull();
                assertThat(row.executionTime()).isEqualTo(ParkedExecutions.PARKED_AT);
            });
            // Several more polls: a parked execution is not taken again.
            Eventually.throughout(Duration.ofSeconds(4), () -> assertThat(ATTEMPTS.get()).isEqualTo(3));
        } finally {
            root.detachAppender(appender);
        }

        Row row = rowOf(instance);
        assertThat(row.consecutiveFailures()).isEqualTo(3);
        assertThat(row.lastFailure()).isNotNull();
        assertThat(row.picked()).isFalse();
        List<String> warnings = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("job_failed")).toList();
        assertThat(warnings).containsExactly(
                "job_failed task=identity.always-fails attempt=1 outcome=retry",
                "job_failed task=identity.always-fails attempt=2 outcome=retry",
                "job_failed task=identity.always-fails attempt=3 outcome=parked");
    }

    @Test
    void aTransientFailureIsRetriedAndEndsInASingleSuccessWithTheRowGone() {
        JobInstanceId instance;
        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            instance = scheduleProbe(FAILS_ONCE);
            Eventually.within(Duration.ofSeconds(90), () -> assertThat(rowOf(instance)).isNull());
        }

        assertThat(SUCCESSES.get(instance.value()).get()).as("the effect exists once").isEqualTo(1);
    }

    @Test
    void theLibraryPolicyThatRemovesTheExecutionLeavesNoRowAndIsWhyItIsNotUsed() {
        AtomicInteger attempts = new AtomicInteger();
        var task = Tasks.oneTime("identity.library-policy", com.confia.shared.jobs.TracedJob.class)
                .onFailure(FailureHandler.<com.confia.shared.jobs.TracedJob>maxRetries(2)
                        .retryEvery(Duration.ofSeconds(1)).thenRemove())
                .execute((instance, context) -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("fails");
                });
        JobType<Probe> type = fastType("identity.library-policy", Probe.class, 3);
        Scheduler scheduler = Scheduler.create(dataSource(), List.of(task)).serializer(new TracedJobSerializer())
                .jdbcCustomization(new PostgreSqlJdbcCustomization(false, false))
                .pollingInterval(Duration.ofSeconds(1)).heartbeatInterval(Duration.ofSeconds(1)).build();
        JobInstanceId instance = scheduleProbe(type);
        scheduler.start();
        try {
            Eventually.within(Duration.ofSeconds(90), () -> assertThat(rowOf(instance)).isNull());
        } finally {
            scheduler.stop();
        }

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(rowOf(instance)).as("deleted: the exhausted execution is discarded without a trace").isNull();
    }
}
