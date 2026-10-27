package com.confia.shared.jobs.infrastructure;

import com.confia.shared.jobs.RetryPolicy;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The failure policy of a one-time job (background-jobs-with-db-scheduler design.md decision 9;
 * ADR-0016 rule 6). While the {@link RetryPolicy} has attempts left it reschedules to {@code now +
 * delay}; when they are spent it parks the execution at {@link ParkedExecutions#PARKED_AT}. It never
 * calls {@code stop()}, which is what the library's {@code MaxRetriesFailureHandler} does: that
 * deletes the execution and drops the task without a trace.
 *
 * <p>Each failure writes one {@code WARN} {@code job_failed} with the task, the attempt and the
 * outcome ({@code retry} or {@code parked}) and nothing of the task data.
 */
public final class BoundedRetryFailureHandler<T> implements FailureHandler<T> {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedRetryFailureHandler.class);

    private final String taskName;
    private final RetryPolicy policy;
    private final Clock clock;

    public BoundedRetryFailureHandler(String taskName, RetryPolicy policy, Clock clock) {
        this.taskName = Objects.requireNonNull(taskName, "taskName");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onFailure(ExecutionComplete complete, ExecutionOperations<T> operations) {
        int failures = complete.getExecution().consecutiveFailures + 1;
        Optional<Duration> delay = policy.delayAfterFailure(failures);
        if (delay.isPresent()) {
            LOG.warn("job_failed task={} attempt={} outcome=retry", taskName, failures);
            operations.reschedule(complete, clock.instant().plus(delay.get()));
        } else {
            LOG.warn("job_failed task={} attempt={} outcome=parked", taskName, failures);
            operations.reschedule(complete, ParkedExecutions.PARKED_AT);
        }
    }
}
