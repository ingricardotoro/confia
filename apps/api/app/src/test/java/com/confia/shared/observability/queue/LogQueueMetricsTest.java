package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * Scenarios BJ36, BJ68 and BJ69 of background-jobs-with-db-scheduler (design.md decision 20): the adapter writes
 * one {@code queue_state} {@code INFO} for each observation, one {@code queue_task_failed} {@code WARN} for each
 * task name with failed executions and one {@code queue_stalled} {@code WARN} when the state is degraded, each
 * with exactly its fields. The snapshot has no task data and no instance identifier to begin with, and the line is
 * written from closed names and numbers only.
 */
class LogQueueMetricsTest {

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LogQueueMetrics.class);
    private final LogQueueMetrics metrics = new LogQueueMetrics();

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            fields.put(pair.key, pair.value);
        }
        return fields;
    }

    private static void assertPlainEvent(ILoggingEvent event, Level level, String name) {
        assertThat(event.getLevel()).isEqualTo(level);
        assertThat(event.getLoggerName()).isEqualTo(LogQueueMetrics.class.getName());
        assertThat(event.getFormattedMessage()).isEqualTo(name);
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void aHealthyQueueWritesOnlyTheStateWithExactlyItsFields() {
        metrics.observed(new QueueSnapshot(1, 3, 2, Map.of(), Duration.ofSeconds(120)));

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertPlainEvent(event, Level.INFO, "queue_state");
            assertThat(fieldsOf(event)).containsExactly(Map.entry("scheduled", 1L), Map.entry("due", 3L),
                    Map.entry("running", 2L), Map.entry("failed", 0L), Map.entry("oldest_due_age_seconds", 120L));
        });
    }

    @Test
    void aQueueWithNoTasksStillWritesTheStateWithZeroes() {
        metrics.observed(QueueSnapshot.empty());

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertPlainEvent(event, Level.INFO, "queue_state");
            assertThat(fieldsOf(event)).containsExactly(Map.entry("scheduled", 0L), Map.entry("due", 0L),
                    Map.entry("running", 0L), Map.entry("failed", 0L), Map.entry("oldest_due_age_seconds", 0L));
        });
    }

    @Test
    void theFailedCountOfTheStateIsTheSumOfEveryTask() {
        metrics.observed(new QueueSnapshot(0, 0, 0, Map.of("identity.a", 2L, "shared.b", 1L), Duration.ZERO));

        assertThat(fieldsOf(logged.list.get(0))).containsEntry("failed", 3L);
    }

    @Test
    void eachTaskNameWithFailuresLeavesOneWarnAfterTheStateInNameOrder() {
        metrics.observed(new QueueSnapshot(0, 0, 0, Map.of("shared.b-task", 1L, "identity.a-task", 2L),
                Duration.ofSeconds(5)));

        assertThat(logged.list).hasSize(3);
        assertPlainEvent(logged.list.get(0), Level.INFO, "queue_state");
        assertPlainEvent(logged.list.get(1), Level.WARN, "queue_task_failed");
        assertThat(fieldsOf(logged.list.get(1)))
                .containsExactly(Map.entry("task", "identity.a-task"), Map.entry("failed", 2L));
        assertPlainEvent(logged.list.get(2), Level.WARN, "queue_task_failed");
        assertThat(fieldsOf(logged.list.get(2)))
                .containsExactly(Map.entry("task", "shared.b-task"), Map.entry("failed", 1L));
    }

    @Test
    void aParkedTaskAnd901SecondsLeaveTheFailureAndTheStalledWarning() {
        metrics.observed(new QueueSnapshot(0, 1, 0, Map.of("identity.password-reset-issuance", 1L),
                Duration.ofSeconds(901)));

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("queue_state", "queue_task_failed", "queue_stalled");
        ILoggingEvent stalled = logged.list.get(2);
        assertPlainEvent(stalled, Level.WARN, "queue_stalled");
        assertThat(fieldsOf(stalled)).containsExactly(Map.entry("oldest_due_age_seconds", 901L));
    }

    @Test
    void anAgeOfNineHundredSecondsLeavesNoStalledWarning() {
        metrics.observed(new QueueSnapshot(0, 1, 0, Map.of("identity.password-reset-issuance", 1L),
                Duration.ofSeconds(900)));

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("queue_state", "queue_task_failed");
    }

    @Test
    void aTaskNameThatIsNotAClosedIdentifierIsWrittenAsUnrecognized() {
        metrics.observed(new QueueSnapshot(0, 0, 0, Map.of("evil\nforged=1 queue_stalled", 1L, "A".repeat(81), 1L),
                Duration.ZERO));

        List<ILoggingEvent> failures = logged.list.stream()
                .filter(event -> event.getFormattedMessage().equals("queue_task_failed")).toList();
        assertThat(failures).hasSize(2).allSatisfy(event ->
                assertThat(fieldsOf(event)).containsEntry("task", "unrecognized").containsEntry("failed", 1L));
    }

    @Test
    void theEventsCarryOnlyNumbersAndClosedNamesAndNeitherTaskDataNorAnInstance() {
        metrics.observed(new QueueSnapshot(4, 3, 2, Map.of("identity.password-reset-issuance", 1L),
                Duration.ofSeconds(950)));

        assertThat(logged.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getMDCPropertyMap()).doesNotContainKeys("institutionId", "instance");
            assertThat(fieldsOf(event).keySet()).doesNotContain("task_data", "instance", "institution", "runId");
            assertThat(fieldsOf(event).values()).allSatisfy(value ->
                    assertThat(value).isInstanceOfAny(Long.class, String.class));
        });
    }
}
