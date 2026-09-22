package com.confia.shared.audit;

import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The 18 signed fields of the canonical audit-row preimage (design.md §6.2, decision 6), plus the
 * chain's {@code prev_hash}, which is concatenated raw at the head of the preimage and is not
 * itself one of the 18 {@code F(v)}-encoded fields. Field order here is the exact order written in
 * {@code shared_audit_row_preimage} (V3__chain_shared_audit_log.sql) and in {@link
 * CanonicalAuditRowSerializer#preimage(CanonicalAuditRow)} — a divergence in order between the two
 * implementations is meant to be a visible line difference on review, never a silent comparator
 * bug (design.md §6.2, point 1).
 *
 * <p>{@code before_value} and {@code after_value} are modeled as {@link JsonNode} rather than raw
 * text: a Java {@code null} reference here means the {@code jsonb} column itself is SQL {@code
 * NULL} ({@code F(v) = 0x00}, the "absent" marker); a present {@link
 * tools.jackson.databind.node.NullNode} means the column holds the JSON literal {@code null}
 * ({@code F(v) = 0x01 || len(4) || "null"}) — the two produce different bytes on the wire, which
 * is exactly the "vacío frente a nulo" divergence family (design.md §6.5, D5).
 *
 * <p>{@code source_ip} is modeled as the plain {@link String} PostgreSQL's {@code host(v)} would
 * return (address only, mask stripped, never re-compressed): {@link CanonicalAuditRowSerializer}
 * only ever appends the mask when it is missing, it never reformats the address itself (design.md
 * §6.3).
 */
public record CanonicalAuditRow(
        byte[] prevHash,
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
        JsonNode beforeValue,
        JsonNode afterValue,
        String reason,
        UUID approverId) {

    @Override
    public String toString() {
        return ("CanonicalAuditRow{prevHash=%s, id=%d, institutionId=%s, occurredAt=%s, "
                + "actorId=%s, actorKind=%s, actorLabel=%s, sourceIp=%s, userAgent=%s, "
                + "requestId=%s, traceId=%s, action=%s, entityType=%s, entityId=%s, outcome=%s, "
                + "beforeValue=%s, afterValue=%s, reason=%s, approverId=%s}")
                .formatted(HexFormat.of().formatHex(prevHash), id, institutionId, occurredAt,
                        actorId, actorKind, actorLabel, sourceIp, userAgent, requestId, traceId,
                        action, entityType, entityId, outcome, beforeValue, afterValue, reason,
                        approverId);
    }
}
