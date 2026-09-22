package com.confia.shared.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * A single row of {@code shared_audit_log} as {@link AuditLogReader} returns it: JDK types only,
 * never {@code org.jooq.JSONB} nor an {@link java.time.OffsetDateTime} straight off the generated
 * record (design.md decision 11). {@code beforeValue}/{@code afterValue} carry the raw {@code
 * jsonb} text exactly as the engine stores it — {@code null} when the column itself is SQL {@code
 * NULL}, the literal text {@code "null"} when the column holds the JSON literal {@code null} — so
 * {@link DefaultAuditChainVerifier} can tell the two apart the same way {@link CanonicalAuditRow}
 * already does for {@link CanonicalAuditRowSerializer}.
 */
public record AuditRowSnapshot(
        long id,
        UUID institutionId,
        Instant occurredAt,
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
        UUID approverId,
        byte[] prevHash,
        byte[] rowHash) {
}
