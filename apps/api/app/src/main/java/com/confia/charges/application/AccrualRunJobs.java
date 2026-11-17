package com.confia.charges.application;

import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.RetryPolicy;
import java.time.Duration;

/**
 * The declaration of the accrual job. Five attempts, an exponential wait from 30 seconds capped at 10 minutes; when they are
 * spent the execution is parked, never dropped, and the run is marked failed.
 */
public final class AccrualRunJobs {

    public static final JobType<AccrualRunJob> TYPE = new JobType<>("charges.accrual-run", AccrualRunJob.class,
            new RetryPolicy(5, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(10)));

    private AccrualRunJobs() {
    }
}
