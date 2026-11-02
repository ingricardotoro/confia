package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqScheduledTaskQueueReader;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The reader of the queue against a real {@code scheduled_tasks} (background-jobs-with-db-scheduler design.md
 * decision 20; scenarios BJ32, BJ36 and BJ67): the rows are seeded in each state the library leaves them in, with
 * the instants chosen by the test so every border is exact, and the reader answers with the counts, the failures of
 * each task and the age of the oldest due task. The age of a task taken by a worker that stopped beating counts as
 * stalled, and the age of a parked one does not count.
 */
class QueueStateReaderIT extends CommittingPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T18:00:00Z");
    private static final Instant PARKED = Instant.parse("9999-01-01T00:00:00Z");
    private static final Duration HEARTBEAT_LIMIT = Duration.ofMinutes(6);
    private static final String RECURRING = "shared.recurring-probe";
    private static final String ONE_TIME = "identity.one-time-probe";

    private QueueStateReader reader() {
        return new JooqScheduledTaskQueueReader(dsl, HEARTBEAT_LIMIT, () -> Set.of(RECURRING));
    }

    @BeforeEach
    void startWithAnEmptyQueue() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("delete from scheduled_tasks")) {
            statement.executeUpdate();
        }
    }

    /** One row of {@code scheduled_tasks} written with the superuser, the way the library would have left it. */
    private static void seed(String task, Instant executionTime, boolean picked, Instant lastHeartbeat,
            Integer consecutiveFailures) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        insert into scheduled_tasks (task_name, task_instance, task_data, execution_time, picked,
                                                     picked_by, last_heartbeat, consecutive_failures, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 1)
                        """)) {
            statement.setString(1, task);
            statement.setString(2, "instance-" + UUID.randomUUID());
            statement.setBytes(3, "DISTINCTIVE-TASK-DATA-9f3a".getBytes(StandardCharsets.UTF_8));
            statement.setObject(4, at(executionTime));
            statement.setBoolean(5, picked);
            statement.setString(6, picked ? "worker-1" : null);
            statement.setObject(7, lastHeartbeat == null ? null : at(lastHeartbeat));
            statement.setObject(8, consecutiveFailures, java.sql.Types.INTEGER);
            statement.executeUpdate();
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void due(String task, Duration ago) throws SQLException {
        seed(task, NOW.minus(ago), false, null, 0);
    }

    @Test
    void depthAgeAndFailuresOfAQueueWithThreeDueOneFutureAndOneExhaustedTask() throws SQLException {
        due(ONE_TIME, Duration.ofSeconds(120));
        due(ONE_TIME, Duration.ofSeconds(60));
        due(ONE_TIME, Duration.ofSeconds(30));
        seed(ONE_TIME, NOW.plusSeconds(3600), false, null, 0);
        seed(ONE_TIME, PARKED, false, null, 5);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.due()).as("the three due tasks that nobody has taken").isEqualTo(3);
        assertThat(snapshot.scheduled()).as("the future one is not due").isEqualTo(1);
        assertThat(snapshot.running()).isZero();
        assertThat(snapshot.failed()).isEqualTo(1);
        assertThat(snapshot.failedByTask()).containsExactly(Map.entry(ONE_TIME, 1L));
        assertThat(snapshot.oldestDueAge()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void everyStateIsCountedAndATaskWhoseHeartbeatLapsedCountsAsStalled() throws SQLException {
        seed(ONE_TIME, NOW.plusSeconds(3600), false, null, 0);
        due(ONE_TIME, Duration.ofSeconds(120));
        due(ONE_TIME, Duration.ofSeconds(60));
        // Taken a long time ago and still beating: it runs, and its age is not that of a due task.
        seed(ONE_TIME, NOW.minusSeconds(5000), true, NOW.minusSeconds(10), 0);
        // Taken by a worker that stopped beating longer ago than the limit allows: stalled.
        seed(ONE_TIME, NOW.minusSeconds(5000), true, NOW.minusSeconds(600), 0);
        seed(ONE_TIME, PARKED, false, null, 5);
        seed(RECURRING, NOW.plusSeconds(30), false, null, 1);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.scheduled()).isEqualTo(1);
        assertThat(snapshot.due()).isEqualTo(2);
        assertThat(snapshot.running()).isEqualTo(2);
        assertThat(snapshot.failed()).isEqualTo(2);
        assertThat(snapshot.failedByTask()).containsOnly(Map.entry(ONE_TIME, 1L), Map.entry(RECURRING, 1L));
        assertThat(snapshot.oldestDueAge()).as("the lapsed heartbeat, older than the oldest due task")
                .isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void theOldestDueTaskSetsTheAgeWhenItIsOlderThanTheLapsedHeartbeat() throws SQLException {
        due(ONE_TIME, Duration.ofSeconds(900));
        seed(ONE_TIME, NOW.minusSeconds(5000), true, NOW.minusSeconds(600), 0);

        assertThat(reader().read(NOW).oldestDueAge()).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void aHeartbeatExactlyAtTheLimitIsStillAlive() throws SQLException {
        seed(ONE_TIME, NOW.minusSeconds(5000), true, NOW.minus(HEARTBEAT_LIMIT), 0);

        QueueSnapshot atTheLimit = reader().read(NOW);

        assertThat(atTheLimit.running()).isEqualTo(1);
        assertThat(atTheLimit.oldestDueAge()).isEqualTo(Duration.ZERO);

        seed(ONE_TIME, NOW.minusSeconds(5000), true, NOW.minus(HEARTBEAT_LIMIT).minusSeconds(1), 0);

        assertThat(reader().read(NOW).oldestDueAge()).isEqualTo(HEARTBEAT_LIMIT.plusSeconds(1));
    }

    @Test
    void aTaskDueExactlyNowIsDueWithAgeZeroAndOneSecondLaterItIsScheduled() throws SQLException {
        seed(ONE_TIME, NOW, false, null, 0);
        seed(ONE_TIME, NOW.plusSeconds(1), false, null, 0);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.due()).isEqualTo(1);
        assertThat(snapshot.scheduled()).isEqualTo(1);
        assertThat(snapshot.oldestDueAge()).isEqualTo(Duration.ZERO);
    }

    @Test
    void aRetryingOneTimeTaskIsNotFailedAndOnlyAParkedOneOrAFailingRecurringOneIs() throws SQLException {
        // A one-time task between two attempts keeps its failures but is not failed until it is parked.
        seed(ONE_TIME, NOW.plusSeconds(30), false, null, 3);
        // A recurring task that never failed, and one whose counter is null, are not failed either.
        seed(RECURRING, NOW.plusSeconds(30), false, null, 0);
        seed(RECURRING, NOW.plusSeconds(30), false, null, null);
        // A recurring task that failed on its last occurrence is.
        seed(RECURRING, NOW.plusSeconds(30), false, null, 2);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.failed()).isEqualTo(1);
        assertThat(snapshot.failedByTask()).containsExactly(Map.entry(RECURRING, 1L));
        assertThat(snapshot.scheduled()).isEqualTo(3);
    }

    @Test
    void aParkedTaskNeverCountsInTheAgeEvenWhenItIsTheOnlyOne() throws SQLException {
        seed(ONE_TIME, PARKED, false, null, 5);
        seed(RECURRING, PARKED, false, null, 1);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.failed()).isEqualTo(2);
        assertThat(snapshot.oldestDueAge()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.due()).isZero();
    }

    @Test
    void aFailingRecurringTaskThatIsDueIsFailedAndStillWaitsForItsTurn() throws SQLException {
        seed(RECURRING, NOW.minusSeconds(200), false, null, 1);

        QueueSnapshot snapshot = reader().read(NOW);

        assertThat(snapshot.failed()).isEqualTo(1);
        assertThat(snapshot.due()).isZero();
        assertThat(snapshot.oldestDueAge()).as("it is overdue, so it counts in the age")
                .isEqualTo(Duration.ofSeconds(200));
    }

    @Test
    void failuresAreCountedForEachTaskName() throws SQLException {
        seed(ONE_TIME, PARKED, false, null, 5);
        seed(ONE_TIME, PARKED, false, null, 5);
        seed("identity.other-probe", PARKED, false, null, 5);
        seed(RECURRING, NOW.plusSeconds(30), false, null, 1);

        assertThat(reader().read(NOW).failedByTask()).containsExactly(Map.entry(ONE_TIME, 2L),
                Map.entry("identity.other-probe", 1L), Map.entry(RECURRING, 1L));
    }

    @Test
    void anEmptyQueueIsAnEmptySnapshot() {
        assertThat(reader().read(NOW)).isEqualTo(QueueSnapshot.empty());
    }

    @Test
    void theRecurringNamesAreReadAtEveryCallSoAJobRegisteredLaterIsSeen() throws SQLException {
        seed("shared.late-recurring", NOW.plusSeconds(30), false, null, 1);
        Set<String> names = new java.util.HashSet<>();
        QueueStateReader reader = new JooqScheduledTaskQueueReader(dsl, HEARTBEAT_LIMIT, () -> names);

        assertThat(reader.read(NOW).failed()).isZero();

        names.add("shared.late-recurring");

        assertThat(reader.read(NOW).failed()).isEqualTo(1);
    }

    @Test
    void theReadingWorksInsideATransactionTheEngineRefusesToWriteIn() throws SQLException {
        due(ONE_TIME, Duration.ofSeconds(10));
        SecurityContext context = new SecurityContext("", "system",
                new InstitutionId(UUID.randomUUID()).value().toString(), UUID.randomUUID().toString());

        QueueSnapshot snapshot = transactionRunner().executeReadOnly(context, () -> reader().read(NOW));

        assertThat(snapshot.due()).isEqualTo(1);
    }

    @Test
    void theEventsOfRowsThatCarryTaskDataLeaveNoFragmentOfItNorAnyInstance() throws SQLException {
        due(ONE_TIME, Duration.ofSeconds(1000));
        seed(ONE_TIME, PARKED, false, null, 5);
        Logger logger = (Logger) LoggerFactory.getLogger(LogQueueMetrics.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            new LogQueueMetrics().observed(reader().read(NOW));
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("queue_state", "queue_task_failed", "queue_stalled");
        assertThat(logged.list).allSatisfy(event -> {
            String everything = event.getFormattedMessage() + event.getKeyValuePairs();
            assertThat(everything).doesNotContain("DISTINCTIVE").doesNotContain("instance-");
        });
    }
}
