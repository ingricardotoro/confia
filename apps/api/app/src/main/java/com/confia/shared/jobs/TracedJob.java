package com.confia.shared.jobs;

import java.util.Objects;

/**
 * The envelope that every task carries as its {@code task_data}: the payload of its type and the
 * {@link JobMeta} that makes it traceable (docs/07 section 3.3; design.md decision 8). It lives in
 * {@code shared.jobs}, next to the port that uses it, and not in {@code
 * shared/observability/context} where the document first placed it.
 *
 * @param payload the data of the job, a {@link Record} whose components pass {@link JobPayloadRules}
 * @param meta identifiers and context
 * @param <T> the payload type
 */
public record TracedJob<T>(T payload, JobMeta meta) {

    public TracedJob {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(meta, "meta");
    }
}
