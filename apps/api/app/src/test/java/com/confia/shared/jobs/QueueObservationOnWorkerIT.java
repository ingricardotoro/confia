package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.observability.queue.LogQueueMetrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The observation of the queue on the real worker (background-jobs-with-db-scheduler design.md decision 20;
 * scenario BJ68): with the production wiring, the reader of {@code scheduled_tasks} over the real database and the
 * real names of the recurring jobs, one occurrence leaves one {@code queue_state} event however many institutions
 * the worker is configured for, and the event counts the task that is running it.
 */
class QueueObservationOnWorkerIT extends WorkerJobIT {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    /** The worker adds nothing of its own to the production registration. */
    @Configuration(proxyBeanMethods = false)
    static class NoFixtures {
    }

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LogQueueMetrics.class);

    @BeforeEach
    void startFromAnEmptyQueue() {
        PurgeProbeRows.run("delete from scheduled_tasks");
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        PurgeProbeRows.run("delete from scheduled_tasks");
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            fields.put(pair.key, pair.value);
        }
        return fields;
    }

    @Test
    void oneOccurrenceWithTwoInstitutionsLeavesOneStateEventThatCountsTheRecurringJobs() {
        try (ConfigurableApplicationContext worker = WorkerHarness.startWith(NoFixtures.class,
                List.of(FIRST, SECOND))) {
            // Starting the worker reconfigures the logging system, which detaches any appender added before, so the
            // appender is added after the start. The first occurrence runs at once and is not the one observed:
            // wait until it ends, then bring the next one forward.
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(PurgeProbeRows.scalar(
                    "select count(*) from scheduled_tasks where task_name = ? and picked = false "
                            + "and last_success is not null", "shared.queue-observation")).isEqualTo(1));
            logged.start();
            logger.addAppender(logged);
            PurgeProbeRows.run("update scheduled_tasks set execution_time = now() - interval '1 second' "
                    + "where picked = false and task_name = ?", "shared.queue-observation");
            Eventually.within(Duration.ofSeconds(60), () -> assertThat(logged.list)
                    .anyMatch(event -> event.getFormattedMessage().equals("queue_state")));
        }

        List<ILoggingEvent> states = logged.list.stream()
                .filter(event -> event.getFormattedMessage().equals("queue_state")).toList();
        assertThat(states).as("one event of state for the occurrence, not one for each institution").hasSize(1);
        ILoggingEvent state = states.get(0);
        Map<String, Object> fields = fieldsOf(state);
        assertThat(state.getLevel()).isEqualTo(Level.INFO);
        assertThat(fields.keySet()).containsExactly("scheduled", "due", "running", "failed",
                "oldest_due_age_seconds");
        long counted = (long) fields.get("scheduled") + (long) fields.get("due") + (long) fields.get("running")
                + (long) fields.get("failed");
        assertThat(counted).as("the eight recurring jobs of the catalog").isEqualTo(8);
        assertThat((long) fields.get("running")).as("the observation itself is running").isGreaterThanOrEqualTo(1);
        assertThat((long) fields.get("failed")).isZero();
        assertThat(logged.list).as("a healthy queue leaves no warning")
                .noneMatch(event -> event.getLevel() == Level.WARN);
    }
}
