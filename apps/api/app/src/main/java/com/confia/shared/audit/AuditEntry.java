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
 * <p>{@code sourceIp} and {@code userAgent} always travel {@code null} in this change: there is no
 * HTTP request yet to read either from (design.md, decision 8, "que este cambio pasa siempre en
 * NULL porque no hay petición HTTP").
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
