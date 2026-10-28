package com.confia.identity.infrastructure;

import com.confia.identity.application.PasswordResetIssuanceJob;
import com.confia.identity.application.PasswordResetIssuanceJobs;
import com.confia.identity.application.PasswordResetIssuanceScheduler;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobScheduler;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Schedules the password-reset issuance as a one-time job (background-jobs-with-db-scheduler
 * design.md decision 18; ADR-0016 rules 2 and 3). It runs inside the request's own transaction, so
 * the task exists if and only if that transaction commits, together with the audit entry.
 *
 * <p>The instance is derived from the institution, the account and the request, so the same
 * request never schedules two issuances, and it holds no address. The envelope names the request
 * as its correlation and the actor kind as staff, with no actor id because the requester is
 * anonymous, and carries no trace context until OpenTelemetry exists.
 */
public final class JobPasswordResetIssuanceScheduler implements PasswordResetIssuanceScheduler {

    private final JobScheduler jobs;
    private final Clock clock;

    public JobPasswordResetIssuanceScheduler(JobScheduler jobs, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void schedule(InstitutionId institutionId, StaffAccountId accountId, UUID requestId) {
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(requestId, "requestId");
        JobInstanceId instance = JobInstanceId.derive(PasswordResetIssuanceJobs.TYPE.name(),
                institutionId.value(), accountId.value(), requestId);
        JobMeta meta = new JobMeta(requestId, requestId, null, ActorKind.STAFF, institutionId.value(), null);
        // ALREADY_SCHEDULED is an answer, not an error: the same request was scheduled before.
        jobs.schedule(PasswordResetIssuanceJobs.TYPE, instance, new PasswordResetIssuanceJob(accountId.value()),
                meta, clock.instant());
    }
}
