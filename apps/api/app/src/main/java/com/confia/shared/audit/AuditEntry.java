package com.confia.shared.audit;

import java.util.UUID;

/**
 * The sixteen insertable columns of {@code shared_audit_log} a caller supplies
 * (V2__create_shared_audit_log.sql; design.md, decision 8, "AuditEntry lleva las dieciséis
 * columnas insertables de la tabla ya entregada"). Deliberately carries no field for {@code id},
 * {@code occurred_at}, {@code prev_hash} nor {@code row_hash}: the first is assigned by the
 * chaining trigger together with the two hashes (V3__chain_shared_audit_log.sql), and {@code
 * occurred_at} is left to the column's own {@code DEFAULT clock_timestamp()} so two entries of the
 * same attempt never share a timestamp (design.md, decision 8). There being no field for any of
 * the four makes it structurally impossible for an adapter to set them, which is exactly what
 * {@link AuditLogWriter}'s contract requires.
 *
 * <p>{@code sourceIp} and {@code userAgent} are left {@code null} by the use cases: the request
 * origin reaches them without a use case asking for it. {@link RequestOriginAuditLogWriter}
 * completes both from the request being served and leaves them {@code null} outside a request;
 * a value a caller does set always wins (web-edge-foundations design.md, decision 13).
 */
public record AuditEntry(
        UUID institutionId,
        UUID actorId,
        String actorKind,
        String actorLabel,
        String sourceIp,
        String userAgent,
        UUID requestId,
        String traceId,
        String action,
        String entityType,
        String entityId,
        String outcome,
        String beforeValue,
        String afterValue,
        String reason,
        UUID approverId) {
}
