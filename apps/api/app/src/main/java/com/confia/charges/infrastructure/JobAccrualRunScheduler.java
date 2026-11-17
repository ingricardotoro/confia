package com.confia.charges.infrastructure;

import com.confia.charges.application.AccrualRunJob;
import com.confia.charges.application.AccrualRunJobs;
import com.confia.charges.application.AccrualRunScheduler;
import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobScheduler;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Schedules the execution of an accrual run as a one-time job (ADR-0016 rules 2 and 3). It runs inside the transaction that
 * creates the run, so the task exists if and only if that transaction commits. The instance is derived from the institution
 * and the run, so the same run never schedules two jobs; the payload is the identifier of the run and nothing else.
 */
public final class JobAccrualRunScheduler implements AccrualRunScheduler {

    private final JobScheduler jobs;
    private final Clock clock;

    public JobAccrualRunScheduler(JobScheduler jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void schedule(InstitutionId institutionId, UUID runId, UUID requestId, UUID requestedBy) {
        JobInstanceId instance = JobInstanceId.derive(AccrualRunJobs.TYPE.name(), institutionId.value(), runId);
        JobMeta meta = new JobMeta(requestId, requestId, requestedBy, ActorKind.STAFF, institutionId.value(), null);
        // ALREADY_SCHEDULED is an answer, not an error: the same run was scheduled before.
        jobs.schedule(AccrualRunJobs.TYPE, instance, new AccrualRunJob(runId), meta, clock.instant());
    }
}
