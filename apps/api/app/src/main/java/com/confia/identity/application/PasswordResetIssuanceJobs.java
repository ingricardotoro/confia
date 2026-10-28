package com.confia.identity.application;

import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.RetryPolicy;
import java.time.Duration;

/**
 * The declaration of the password-reset issuance job (background-jobs-with-db-scheduler design.md
 * decision 18). Five attempts, an exponential wait from 30 seconds capped at 5 minutes; when they
 * are spent the execution is parked, never dropped.
 */
public final class PasswordResetIssuanceJobs {

    public static final JobType<PasswordResetIssuanceJob> TYPE = new JobType<>(
            "identity.password-reset-issuance", PasswordResetIssuanceJob.class,
            new RetryPolicy(5, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5)));

    private PasswordResetIssuanceJobs() {
    }
}
