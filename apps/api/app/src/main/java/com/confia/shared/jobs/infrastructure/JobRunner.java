package com.confia.shared.jobs.infrastructure;

import com.confia.shared.jobs.JobContext;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.jobs.TracedJob;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * Runs one execution of a one-time job (background-jobs-with-db-scheduler design.md decision 10).
 * It puts the context of the job in the MDC ({@code jobTask}, {@code jobInstance}, {@code
 * correlationId}, {@code requestId}, {@code institutionId} and {@code actorKind=system}; the actor of
 * the envelope stays in the audit trail and out of the log), converts the payload with the strict
 * mapper, calls the handler, and turns any failure into a {@link JobExecutionFailedException}. The MDC
 * is cleared in a {@code finally} block, because the threads of the executor are pooled.
 *
 * <p>The handler runs as the {@code system} actor in the institution of the task and opens its own
 * transaction; the runner holds none, so a handler never joins the library's bookkeeping.
 */
public final class JobRunner {

    private final TracedJobSerializer serializer;

    public JobRunner(TracedJobSerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    /**
     * @param attempt the attempt number, from 1
     * @throws JobExecutionFailedException when the data or the handler fails
     */
    public <T extends Record> void run(OneTimeJob<T> job, String instanceId, TracedJob<?> data, int attempt) {
        String name = job.name();
        try {
            JobMeta meta = data.meta();
            MDC.put("jobTask", name);
            MDC.put("jobInstance", instanceId);
            MDC.put("correlationId", meta.correlationId().toString());
            MDC.put("requestId", meta.requestId().toString());
            MDC.put("institutionId", meta.institutionId().toString());
            MDC.put("actorKind", "system");
            T payload = serializer.payloadOf(name, job.type().payloadClass(), data);
            job.handler().handle(payload, new JobContext(name, new JobInstanceId(instanceId), meta, attempt));
        } catch (RuntimeException e) {
            throw JobExecutionFailedException.of(name, e);
        } finally {
            MDC.clear();
        }
    }
}
