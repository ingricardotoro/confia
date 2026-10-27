package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Exit criterion 5, first half (background-jobs-with-db-scheduler design.md decision 6; scenarios
 * BJ01, BJ02, BJ03 and BJ05): a job scheduled inside a transaction exists if and only if that
 * transaction commits, because the client writes through the same proxy as the business data.
 *
 * <p>Probe P-7 is observed here, not assumed: the client builder offers no way to turn on its own
 * commit and fixes {@code commitWhenAutocommitDisabled} to {@code false}, so inside the transaction
 * the row is invisible to every other connection and a rollback removes it.
 *
 * <p><b>Negative control (BJ05).</b> The same checks run against a client built over the raw {@code
 * DataSource}, without the proxy. It writes on another connection in autocommit, so the row survives
 * the rollback and the check fails: the test does not pass for lack of anything to detect.
 */
class JobSchedulingAtomicityIT extends CommittingPostgresIntegrationTest {

    private final TracedJobSerializer serializer = new TracedJobSerializer();

    private JobScheduler realScheduler() {
        return new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()), serializer);
    }

    /** A scheduler for the negative control: the library's client on the raw data source. */
    private JobScheduler rawClientScheduler() {
        SchedulerClient raw = SchedulerClient.Builder.create(dataSource()).serializer(serializer)
                .jdbcCustomization(new PostgreSqlJdbcCustomization(false, false)).build();
        return new JobScheduler() {
            @Override
            public <T extends Record> ScheduleOutcome schedule(JobType<T> type, JobInstanceId instance, T payload,
                    JobMeta meta, Instant runAt) {
                return raw.scheduleIfNotExists(new TaskInstance<>(type.name(), instance.value(),
                        new TracedJob<>(payload, meta)), runAt) ? ScheduleOutcome.SCHEDULED
                        : ScheduleOutcome.ALREADY_SCHEDULED;
            }
        };
    }

    private void scheduleAndWriteAudit(JobScheduler scheduler, UUID account, UUID request) {
        scheduler.schedule(TYPE, SchedulingFixtures.instanceOf(INSTITUTION, account, request), new Probe(account),
                SchedulingFixtures.metaOf(INSTITUTION, request), Instant.parse("2026-10-25T12:00:00Z"));
        new JooqAuditLogWriter(dsl).append(SchedulingFixtures.auditEntryOf(INSTITUTION, request));
    }

    // --- BJ01 -----------------------------------------------------------------------------------

    @Test
    void aCommittedTransactionLeavesTheTaskWithItsDocumentAndTheAuditEntry() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = realScheduler();

        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () -> {
            scheduleAndWriteAudit(scheduler, account, request);
            return null;
        });

        assertThat(SchedulingFixtures.tasksOf(instance)).isEqualTo(1);
        assertThat(SchedulingFixtures.auditRowsOf(INSTITUTION, request)).isEqualTo(1);
        assertThat(SchedulingFixtures.taskDataOf(instance)).startsWith("{\"payload\":{\"accountId\":\"" + account
                + "\"},\"meta\":{\"correlationId\":\"").contains("\"requestId\":\"" + request + "\"")
                .contains("\"actorKind\":\"STAFF\"").contains("\"institutionId\":\"" + INSTITUTION + "\"");
    }

    // --- BJ02 -----------------------------------------------------------------------------------

    @Test
    void aRolledBackTransactionLeavesNeitherTheTaskNorTheAuditEntry() {
        assertARolledBackTransactionLeavesNothing(realScheduler());
    }

    private void assertARolledBackTransactionLeavesNothing(JobScheduler scheduler) {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);

        assertThatThrownBy(() -> transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request),
                () -> {
                    scheduleAndWriteAudit(scheduler, account, request);
                    throw new IllegalStateException("the business operation failed after scheduling");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(SchedulingFixtures.tasksOf(instance)).as("no task after the rollback").isZero();
        assertThat(SchedulingFixtures.auditRowsOf(INSTITUTION, request)).as("no audit entry either").isZero();
    }

    // --- BJ03 and probe P-7 ---------------------------------------------------------------------

    @Test
    void theTaskIsInvisibleToEveryOtherConnectionUntilTheCommit() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = realScheduler();
        AtomicLong visibleBeforeTheCommit = new AtomicLong(-1);

        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () -> {
            scheduleAndWriteAudit(scheduler, account, request);
            visibleBeforeTheCommit.set(SchedulingFixtures.tasksOf(instance));
            return null;
        });

        assertThat(visibleBeforeTheCommit.get())
                .as("probe P-7: the client did not commit by itself, so another connection sees nothing")
                .isZero();
        assertThat(SchedulingFixtures.tasksOf(instance)).as("visible after the commit").isEqualTo(1);
    }

    // --- BJ05: the negative control -------------------------------------------------------------

    @Test
    void aClientBuiltOnTheRawDataSourceLeavesTheTaskAfterTheRollbackAndTheCheckDetectsIt() {
        assertThatThrownBy(() -> assertARolledBackTransactionLeavesNothing(rawClientScheduler()))
                .as("the same check that passes with the real client fails with the raw one")
                .isInstanceOf(AssertionError.class).hasMessageContaining("no task after the rollback");
    }

}
