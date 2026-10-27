package com.confia.shared.jobs;

import java.util.Objects;
import java.util.UUID;

/**
 * The context half of the task envelope, {@code {"payload":{…},"meta":{…}}} (docs/07 section 3.3;
 * background-jobs-with-db-scheduler design.md decision 6). Identifiers only: nothing here is
 * personal data.
 *
 * @param correlationId groups every event of the work this job belongs to
 * @param requestId the request that caused the job; the handler's security context carries it
 * @param actorId who asked, or {@code null} when the requester is anonymous
 * @param actorKind the kind of actor that asked
 * @param institutionId the institution the job belongs to; the handler runs inside it
 * @param traceparent the W3C trace context, or {@code null} until OpenTelemetry exists
 */
public record JobMeta(UUID correlationId, UUID requestId, UUID actorId, ActorKind actorKind,
        UUID institutionId, String traceparent) {

    public JobMeta {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actorKind, "actorKind");
        Objects.requireNonNull(institutionId, "institutionId");
    }
}
