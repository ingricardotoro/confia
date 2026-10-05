package com.confia.shared.audit;

import com.confia.shared.security.RequestOrigin;
import java.util.Objects;
import java.util.Optional;

/**
 * Gives every audit entry written while a request is served the address and the agent that request
 * came from (web-edge-foundations design.md, decision 13). It wraps the writer that persists
 * entries and changes nothing about how it persists them.
 *
 * <p>When a {@link RequestOrigin} is bound and an entry carries no {@code sourceIp} or no {@code
 * userAgent}, those fields are completed from it; a value the caller set always wins. Outside a
 * request (a background job, a test without the web edge) nothing is bound and the entry passes
 * untouched, both fields {@code null}. The origin is read from a {@link ScopedValue}, which exists
 * only for the length of one request, so no entry can carry the origin of another request.
 *
 * <p>This package depends on {@code shared.security} for the origin and never on the web edge or on
 * a business module: the use cases of identity are not changed and receive the origin without
 * asking for it.
 */
public final class RequestOriginAuditLogWriter implements AuditLogWriter {

    private final AuditLogWriter delegate;

    public RequestOriginAuditLogWriter(AuditLogWriter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void append(AuditEntry entry) {
        Optional<RequestOrigin> origin = RequestOrigin.current();
        if (origin.isEmpty()) {
            delegate.append(entry);
            return;
        }
        String sourceIp = entry.sourceIp() != null ? entry.sourceIp()
                : origin.get().clientAddress() == null ? null
                        : origin.get().clientAddress().canonical();
        String userAgent = entry.userAgent() != null ? entry.userAgent() : origin.get().userAgent();
        if (Objects.equals(sourceIp, entry.sourceIp())
                && Objects.equals(userAgent, entry.userAgent())) {
            delegate.append(entry);
            return;
        }
        delegate.append(new AuditEntry(entry.institutionId(), entry.actorId(), entry.actorKind(),
                entry.actorLabel(), sourceIp, userAgent, entry.requestId(), entry.traceId(),
                entry.action(), entry.entityType(), entry.entityId(), entry.outcome(),
                entry.beforeValue(), entry.afterValue(), entry.reason(), entry.approverId()));
    }
}
