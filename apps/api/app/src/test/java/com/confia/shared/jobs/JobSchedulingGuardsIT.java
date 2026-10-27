package com.confia.shared.jobs;

import static com.confia.shared.jobs.SchedulingFixtures.INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.OTHER_INSTITUTION;
import static com.confia.shared.jobs.SchedulingFixtures.TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.JobDataRejectedException;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The refusals and idempotence of {@link DbSchedulerJobScheduler} that need the engine
 * (background-jobs-with-db-scheduler design.md decision 6; scenarios BJ14, BJ15, BJ44, BJ45 and BJ53).
 * Those that do not are in {@code JobSchedulingGuardsTest}.
 */
class JobSchedulingGuardsIT extends CommittingPostgresIntegrationTest {

    private static final Instant RUN_AT = Instant.parse("2026-10-25T12:00:00Z");

    private final TracedJobSerializer serializer = new TracedJobSerializer();

    private JobScheduler scheduler() {
        return new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()), serializer);
    }

    // --- BJ44 -----------------------------------------------------------------------------------

    @Test
    void schedulingTheSameInstanceAgainInTheSameTransactionAnswersAlreadyScheduledAndCommits() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = scheduler();
        List<ScheduleOutcome> outcomes = new ArrayList<>();

        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () -> {
            outcomes.add(scheduler.schedule(TYPE, instance, new Probe(account),
                    SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT));
            new JooqAuditLogWriter(dsl).append(SchedulingFixtures.auditEntryOf(INSTITUTION, request));
            outcomes.add(scheduler.schedule(TYPE, instance, new Probe(account),
                    SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT));
            return null;
        });

        assertThat(outcomes).containsExactly(ScheduleOutcome.SCHEDULED, ScheduleOutcome.ALREADY_SCHEDULED);
        assertThat(SchedulingFixtures.tasksOf(instance)).as("exactly one row for the instance").isEqualTo(1);
        assertThat(SchedulingFixtures.auditRowsOf(INSTITUTION, request))
                .as("the business transaction was not aborted by the second call").isEqualTo(1);
    }

    // --- BJ14 -----------------------------------------------------------------------------------

    @Test
    void schedulingTheSameInstanceInTwoCommittedTransactionsLeavesOneRow() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = scheduler();
        List<ScheduleOutcome> outcomes = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () ->
                    outcomes.add(scheduler.schedule(TYPE, instance, new Probe(account),
                            SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT)));
        }

        assertThat(outcomes).containsExactly(ScheduleOutcome.SCHEDULED, ScheduleOutcome.ALREADY_SCHEDULED);
        assertThat(SchedulingFixtures.tasksOf(instance)).isEqualTo(1);
    }

    // --- BJ15 -----------------------------------------------------------------------------------

    @Test
    void twoRequestsForTheSameAccountMakeTwoRowsWithDifferentOpaqueInstances() {
        UUID account = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        JobInstanceId firstInstance = SchedulingFixtures.instanceOf(INSTITUTION, account, first);
        JobInstanceId secondInstance = SchedulingFixtures.instanceOf(INSTITUTION, account, second);
        JobScheduler scheduler = scheduler();

        for (UUID request : List.of(first, second)) {
            transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () ->
                    scheduler.schedule(TYPE, SchedulingFixtures.instanceOf(INSTITUTION, account, request),
                            new Probe(account), SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT));
        }

        assertThat(firstInstance).isNotEqualTo(secondInstance);
        assertThat(SchedulingFixtures.tasksOf(firstInstance)).isEqualTo(1);
        assertThat(SchedulingFixtures.tasksOf(secondInstance)).isEqualTo(1);
        assertThat(SchedulingFixtures.tasks()).isEqualTo(2);
    }

    // --- BJ45 -----------------------------------------------------------------------------------

    @Test
    void anEnvelopeOfAnotherInstitutionIsRefusedAndLeavesNoRow() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = scheduler();

        assertThatThrownBy(() -> transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request),
                () -> scheduler.schedule(TYPE, instance, new Probe(account),
                        SchedulingFixtures.metaOf(OTHER_INSTITUTION, request), RUN_AT)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity.scheduling-probe")
                .hasMessageContaining("not the institution of the transaction");

        assertThat(SchedulingFixtures.tasks()).isZero();
    }

    @Test
    void aTransactionWithoutAnInstitutionRefusesEveryEnvelope() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = scheduler();
        SecurityContext withoutInstitution = new SecurityContext("", "system", "", request.toString());

        assertThatThrownBy(() -> transactionRunner().execute(withoutInstitution, () -> scheduler.schedule(TYPE,
                instance, new Probe(account), SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT)))
                .isExactlyInstanceOf(IllegalArgumentException.class);

        assertThat(SchedulingFixtures.tasks()).isZero();
    }

    @Test
    void anInstitutionWrittenInUpperCaseIsTheSameInstitution() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobInstanceId instance = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobScheduler scheduler = scheduler();
        SecurityContext upperCase = new SecurityContext("", "system", INSTITUTION.toString().toUpperCase(),
                request.toString());

        transactionRunner().execute(upperCase, () -> scheduler.schedule(TYPE, instance, new Probe(account),
                SchedulingFixtures.metaOf(INSTITUTION, request), RUN_AT));

        assertThat(SchedulingFixtures.tasksOf(instance)).isEqualTo(1);
    }

    // --- BJ53 -----------------------------------------------------------------------------------

    private JobMeta paddedMeta(UUID request, int traceparentLength) {
        return new JobMeta(UUID.randomUUID(), request, null, ActorKind.STAFF, INSTITUTION,
                "t".repeat(traceparentLength));
    }

    @Test
    void aDocumentOfExactlyFourKibIsScheduledAndOneByteMoreIsRefusedWithoutARow() {
        UUID account = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        JobScheduler scheduler = scheduler();
        int base = serializer.serialize(TYPE.name(), new TracedJob<>(new Probe(account), paddedMeta(request, 0)))
                .length;
        int exactly = TracedJobSerializer.MAX_DOCUMENT_BYTES - base;
        JobInstanceId fits = SchedulingFixtures.instanceOf(INSTITUTION, account, request);
        JobInstanceId tooBig = SchedulingFixtures.instanceOf(INSTITUTION, UUID.randomUUID(), request);

        transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request), () ->
                scheduler.schedule(TYPE, fits, new Probe(account), paddedMeta(request, exactly), RUN_AT));
        assertThatThrownBy(() -> transactionRunner().execute(SchedulingFixtures.contextOf(INSTITUTION, request),
                () -> scheduler.schedule(TYPE, tooBig, new Probe(account), paddedMeta(request, exactly + 1),
                        RUN_AT))).isExactlyInstanceOf(JobDataRejectedException.class)
                .hasMessageContaining("identity.scheduling-probe");

        assertThat(SchedulingFixtures.tasksOf(fits)).isEqualTo(1);
        assertThat(SchedulingFixtures.tasksOf(tooBig)).isZero();
        assertThat(SchedulingFixtures.taskDataOf(fits).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSize(TracedJobSerializer.MAX_DOCUMENT_BYTES);
    }
}
