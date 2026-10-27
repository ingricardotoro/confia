package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;

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
import java.util.UUID;

/**
 * Base of the integration tests that run the real worker against the shared database: it schedules
 * from the administrative side (the real {@link DbSchedulerJobScheduler} inside a transaction of the
 * real {@code TransactionRunner}) and reads the state of an execution as the superuser, which sees
 * the row whatever the institution of the transaction.
 */
abstract class WorkerJobIT extends CommittingPostgresIntegrationTest {

    /** The state of one row of {@code scheduled_tasks}, or {@code null} when it is gone. */
    record Row(Instant executionTime, int consecutiveFailures, Instant lastFailure, boolean picked,
            String pickedBy) { }

    /** A job type whose attempts are fast: three attempts one second apart. */
    static <T extends Record> JobType<T> fastType(String name, Class<T> payload, int attempts) {
        return new JobType<>(name, payload,
                new RetryPolicy(attempts, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)));
    }

    protected <T extends Record> JobInstanceId schedule(JobType<T> type, T payload, UUID institution) {
        UUID request = UUID.randomUUID();
        JobInstanceId instance = JobInstanceId.derive(type.name(), institution, UUID.randomUUID(), request);
        JobScheduler scheduler = new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()),
                new TracedJobSerializer());
        transactionRunner().execute(SchedulingFixtures.contextOf(institution, request), () ->
                scheduler.schedule(type, instance, payload, SchedulingFixtures.metaOf(institution, request),
                        Instant.now().minusSeconds(5)));
        return instance;
    }

    protected JobInstanceId scheduleProbe(JobType<Probe> type) {
        return schedule(type, new Probe(UUID.randomUUID()), INSTITUTION);
    }

    static Row rowOf(JobInstanceId instance) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select execution_time, consecutive_failures, last_failure, picked, picked_by "
                                + "from scheduled_tasks where task_instance = ?")) {
            statement.setString(1, instance.value());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Row(result.getTimestamp(1).toInstant(), result.getInt(2),
                        result.getTimestamp(3) == null ? null : result.getTimestamp(3).toInstant(),
                        result.getBoolean(4), result.getString(5));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
