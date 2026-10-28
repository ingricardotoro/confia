package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.PasswordResetIssuanceJob;
import com.confia.identity.application.PasswordResetIssuanceJobs;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobScheduler;
import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.RetryPolicy;
import com.confia.shared.jobs.ScheduleOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JobPasswordResetIssuanceScheduler} against a recording {@link JobScheduler}
 * (background-jobs-with-db-scheduler design.md decision 18; scenarios I229 and I231): what the
 * adapter hands to the port, with no database. The transaction is {@code
 * RequestPasswordResetSchedulingIT}'s.
 */
class JobPasswordResetIssuanceSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());

    private final RecordingJobScheduler jobs = new RecordingJobScheduler();
    private final JobPasswordResetIssuanceScheduler adapter = new JobPasswordResetIssuanceScheduler(jobs,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theJobTypeIsTheDeclaredNameWithFiveBoundedAttempts() {
        assertThat(PasswordResetIssuanceJobs.TYPE.name()).isEqualTo("identity.password-reset-issuance");
        assertThat(PasswordResetIssuanceJobs.TYPE.payloadClass()).isEqualTo(PasswordResetIssuanceJob.class);
        assertThat(PasswordResetIssuanceJobs.TYPE.retry())
                .isEqualTo(new RetryPolicy(5, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5)));
        assertThat(PasswordResetIssuanceJob.class.getRecordComponents())
                .extracting(component -> component.getName()).containsExactly("accountId");
    }

    /** Scenario I229: instance from the three identifiers, payload only the account, exact meta. */
    @Test
    void itSchedulesNowWithTheDerivedInstanceTheAccountPayloadAndTheExactMeta() {
        UUID requestId = UUID.randomUUID();

        adapter.schedule(INSTITUTION, ACCOUNT, requestId);

        assertThat(jobs.calls).singleElement().satisfies(call -> {
            assertThat(call.type()).isSameAs(PasswordResetIssuanceJobs.TYPE);
            assertThat(call.instance()).isEqualTo(JobInstanceId.derive(
                    "identity.password-reset-issuance", INSTITUTION.value(), ACCOUNT.value(), requestId));
            assertThat(call.payload()).isEqualTo(new PasswordResetIssuanceJob(ACCOUNT.value()));
            assertThat(call.meta()).isEqualTo(new JobMeta(requestId, requestId, null, ActorKind.STAFF,
                    INSTITUTION.value(), null));
            assertThat(call.runAt()).isEqualTo(NOW);
        });
    }

    /** Scenario I231: a second request for the same account is another instance. */
    @Test
    void anotherRequestForTheSameAccountIsAnotherInstance() {
        adapter.schedule(INSTITUTION, ACCOUNT, UUID.randomUUID());
        adapter.schedule(INSTITUTION, ACCOUNT, UUID.randomUUID());

        assertThat(jobs.calls).extracting(Call::instance).doesNotHaveDuplicates().hasSize(2);
    }

    /** Scenario I231: scheduling what already exists is an answer, not a failure. */
    @Test
    void anAlreadyScheduledInstanceIsNotAnError() {
        jobs.outcome = ScheduleOutcome.ALREADY_SCHEDULED;

        adapter.schedule(INSTITUTION, ACCOUNT, UUID.randomUUID());

        assertThat(jobs.calls).hasSize(1);
    }

    record Call(JobType<?> type, JobInstanceId instance, Record payload, JobMeta meta, Instant runAt) {
    }

    private static final class RecordingJobScheduler implements JobScheduler {
        final List<Call> calls = new ArrayList<>();
        ScheduleOutcome outcome = ScheduleOutcome.SCHEDULED;

        @Override
        public <T extends Record> ScheduleOutcome schedule(JobType<T> type, JobInstanceId instance, T payload,
                JobMeta meta, Instant runAt) {
            calls.add(new Call(type, instance, payload, meta, runAt));
            return outcome;
        }
    }
}
