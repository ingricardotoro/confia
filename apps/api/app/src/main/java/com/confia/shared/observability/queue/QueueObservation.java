package com.confia.shared.observability.queue;

import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The recurring job of the worker that observes the queue every 60 seconds (background-jobs-with-db-scheduler
 * design.md decision 20; scenario BJ68). An occurrence reads the queue in a read-only transaction and hands the
 * reading to {@link QueueMetrics}.
 *
 * <p>The worker calls a recurring job once for each configured institution, but the queue is one for the whole
 * system, so the first institution of an occurrence reads and publishes and the others of the same occurrence do
 * nothing: one event of state for each occurrence, however many institutions there are. A reading that fails
 * marks nothing as done, so the next institution of the occurrence tries again, and the occurrence still ends
 * as failed like any recurring job.
 *
 * <p><b>Declared limit.</b> The observation runs in the same queue it observes. If the whole executor stops, the
 * event stops appearing, and nothing here can say so. The observability changes must therefore alert on the
 * <em>absence</em> of {@value LogQueueMetrics#STATE_EVENT} for more than five minutes.
 */
public final class QueueObservation {

    public static final String TASK_NAME = "shared.queue-observation";
    public static final Duration INTERVAL = Duration.ofSeconds(60);

    private final TransactionRunner runner;
    private final QueueStateReader reader;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final AtomicReference<UUID> lastObservedRun = new AtomicReference<>();

    public QueueObservation(TransactionRunner runner, QueueStateReader reader, QueueMetrics metrics, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Every 60 seconds, after the previous occurrence ends. */
    public RecurringJob job() {
        return new RecurringJob(TASK_NAME, new JobCalendar.Every(INTERVAL), this::observe);
    }

    private void observe(InstitutionId institution, RecurringRun run) {
        if (run.runId().equals(lastObservedRun.get())) {
            return;
        }
        QueueSnapshot snapshot = runner.executeReadOnly(run.systemContextFor(institution),
                () -> reader.read(clock.instant()));
        metrics.observed(snapshot);
        lastObservedRun.set(run.runId());
    }
}
