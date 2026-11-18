package com.confia.charges.application;

import com.confia.shared.jobs.JobContext;
import com.confia.shared.jobs.JobHandler;
import java.util.Objects;

/**
 * Runs one execution of the accrual job in the worker (ADR-0016). It executes the run as the {@code system} actor in the
 * institution of the task; the run in the database is the source of truth, never the task data. A thrown failure makes the
 * job retry, and the run resumes from the students it already finished; when the last attempt fails the run is marked failed
 * before the failure is rethrown, so the execution is parked and the run says so, and nothing is dropped.
 */
public final class AccrualRunJobHandler implements JobHandler<AccrualRunJob> {

    private final ExecuteAccrualRun execution;

    public AccrualRunJobHandler(ExecuteAccrualRun execution) {
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    @Override
    public void handle(AccrualRunJob payload, JobContext context) {
        try {
            execution.execute(context.systemContext(), payload.runId());
        } catch (RuntimeException failure) {
            if (context.attempt() >= AccrualRunJobs.TYPE.retry().maxAttempts()) {
                try {
                    execution.markFailed(context.systemContext(), payload.runId());
                } catch (RuntimeException secondary) {
                    failure.addSuppressed(secondary);
                }
            }
            throw failure;
        }
    }
}
