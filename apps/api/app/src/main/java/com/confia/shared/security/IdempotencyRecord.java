package com.confia.shared.security;

import java.time.Instant;

/**
 * A single row of {@code shared_idempotency_key} as {@link IdempotencyRecordStore} returns it: JDK
 * types only (design.md, section 6.2). {@code responseBody} travels as the raw {@code jsonb} text
 * exactly as the engine stores it, the same pattern {@code AuditRowSnapshot} already established —
 * a {@code org.jooq.JSONB} leaving {@code infrastructure} through this port would violate R1.
 */
public record IdempotencyRecord(
        String requestHash,
        String status,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant completedAt,
        Instant expiresAt) {
}
