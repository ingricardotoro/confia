package com.confia.shared.jobs.infrastructure;

import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobPayloadRules;
import com.confia.shared.jobs.JobScheduler;
import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.ScheduleOutcome;
import com.confia.shared.jobs.TracedJob;
import com.confia.shared.security.TransactionalDataSource;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * The adapter of {@link JobScheduler} for the administrative and portal processes
 * (background-jobs-with-db-scheduler design.md decisions 5 and 6; ADR-0016 rules 1 and 3). It builds
 * the library's {@link SchedulerClient} here, inside the adapter, and never exposes it as a bean:
 * the client is a lightweight writer of {@code scheduled_tasks}, and it is built over the one
 * transaction-aware proxy of the process ({@link TransactionalDataSource}), the same object the
 * {@code DSLContext} uses. A task is therefore written on the connection and in the transaction of
 * the business operation, and exists if and only if that transaction commits. The client never
 * commits by itself: its builder fixes {@code commitWhenAutocommitDisabled} to {@code false}
 * (probe P-7), so on a connection with autocommit disabled the commit is the transaction's.
 *
 * <p>{@link #schedule} runs these steps in this order, and each one refuses before the next begins:
 *
 * <ol>
 *   <li>a transaction is active ({@link TransactionalDataSource#requireActiveTransaction});
 *   <li>the payload obeys {@link JobPayloadRules};
 *   <li>{@code meta.institutionId} is the institution of the transaction, which is the {@code
 *       app.institution_id} that {@code TransactionRunner} set as its first statement, read back from
 *       the transaction itself rather than trusted from the caller;
 *   <li>the envelope serializes within the size limit of {@link TracedJobSerializer};
 *   <li>{@code scheduleIfNotExists}, which answers {@link ScheduleOutcome#SCHEDULED} or {@link
 *       ScheduleOutcome#ALREADY_SCHEDULED}.
 * </ol>
 *
 * <p>An instance that already exists is never an insert that fails: a duplicate key would abort the
 * whole business transaction in PostgreSQL. The library reads the row first, and an existing instance
 * is reported as {@code ALREADY_SCHEDULED} (scenario BJ44). If the transaction is retried after a
 * serialization failure or a deadlock, the earlier attempt rolled its insert back with everything
 * else, and the new attempt schedules the same instance again: one task (scenario BJ04).
 */
public final class DbSchedulerJobScheduler implements JobScheduler {

    private static final String INSTITUTION_OF_THE_TRANSACTION_SQL =
            "select current_setting('app.institution_id', true)";

    private final DataSource transactionAwareDataSource;
    private final TracedJobSerializer serializer;
    private final SchedulerClient client;

    public DbSchedulerJobScheduler(TransactionalDataSource dataSource, TracedJobSerializer serializer) {
        this.transactionAwareDataSource = Objects.requireNonNull(dataSource, "dataSource").proxy();
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        // The PostgreSQL dialect is explicit: detecting it would open a connection while the
        // context starts, and a process must start with the database down.
        this.client = SchedulerClient.Builder.create(transactionAwareDataSource)
                .serializer(serializer)
                .jdbcCustomization(new PostgreSqlJdbcCustomization(false, false))
                .build();
    }

    @Override
    public <T extends Record> ScheduleOutcome schedule(JobType<T> type, JobInstanceId instance, T payload,
            JobMeta meta, Instant runAt) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(runAt, "runAt");

        TransactionalDataSource.requireActiveTransaction("schedule the job " + type.name());
        JobPayloadRules.validate(type, payload);
        requireTheInstitutionOfTheTransaction(type, meta);
        TracedJob<T> job = new TracedJob<>(payload, meta);
        // Refuses a document over the size limit here, naming the task. The library serializes it
        // again, without a task name, when it writes the row.
        serializer.serialize(type.name(), job);

        boolean created = client.scheduleIfNotExists(new TaskInstance<>(type.name(), instance.value(), job), runAt);
        return created ? ScheduleOutcome.SCHEDULED : ScheduleOutcome.ALREADY_SCHEDULED;
    }

    /** The proxy this adapter writes through: the one of the process. Exposed for scenario BJ46. */
    public DataSource dataSource() {
        return transactionAwareDataSource;
    }

    private void requireTheInstitutionOfTheTransaction(JobType<?> type, JobMeta meta) {
        UUID institution = institutionOfTheTransaction();
        if (!meta.institutionId().equals(institution)) {
            throw new IllegalArgumentException("the institution of the job '" + type.name()
                    + "' is not the institution of the transaction that schedules it");
        }
    }

    /** The institution the transaction runs in, or {@code null} when it has none or it is not a UUID. */
    private UUID institutionOfTheTransaction() {
        try (Connection connection = transactionAwareDataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(INSTITUTION_OF_THE_TRANSACTION_SQL);
                ResultSet result = statement.executeQuery()) {
            result.next();
            String value = result.getString(1);
            return value == null || value.isEmpty() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        } catch (SQLException e) {
            throw new UncategorizedSQLException("read the institution of the transaction",
                    INSTITUTION_OF_THE_TRANSACTION_SQL, e);
        }
    }
}
