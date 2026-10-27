package com.confia.shared.jobs;

import com.confia.shared.security.SecurityContext;
import java.util.Objects;

/**
 * What a {@link JobHandler} knows about the execution it runs in (design.md decision 7).
 *
 * @param taskName the job type name
 * @param instance the execution being run
 * @param meta the identifiers and context the job was scheduled with
 * @param attempt the attempt number, from 1
 */
public record JobContext(String taskName, JobInstanceId instance, JobMeta meta, int attempt) {

    public JobContext {
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(meta, "meta");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
    }

    /**
     * The security context the handler's transaction opens with: no actor id, the {@code system}
     * actor kind, the institution of the task and its request id. The actor recorded in {@link
     * #meta()} is who asked, and stays in the audit trail; it is never the identity that runs.
     */
    public SecurityContext systemContext() {
        return new SecurityContext("", "system", meta.institutionId().toString(), meta.requestId().toString());
    }
}
