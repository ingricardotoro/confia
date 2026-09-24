package com.confia.shared.security;

import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.Optional;

/**
 * Port over {@code shared_idempotency_key} (design.md, section 6.2). The single adapter, {@link
 * com.confia.shared.infrastructure.JooqIdempotencyRecordStore}, is the only place jOOQ touches this
 * table (ADR-0015 rule 4, R1) — this interface and every type it returns stay JDK-only, so a future
 * caller such as {@code IdempotentExecutor} never depends on {@code org.jooq}.
 *
 * <p>Every method may throw {@link IdempotencyConflictException} with {@link
 * IdempotencyConflictException.Reason#WAIT_EXHAUSTED} when the row-level lock this method takes (or
 * waits on) is not granted before the transaction's {@code lock_timeout} expires (design.md,
 * decision 4 and decision 5). {@link #insertInProgress} additionally throws {@link
 * IdempotencyMarkerAlreadyExists} when the primary key is already taken by a row a concurrent
 * transaction already committed (design.md, decision 5, {@code SQLState 23505}). Neither exception
 * is ever discriminated by message text — only by {@code SQLState} in the adapter's cause chain.
 */
public interface IdempotencyRecordStore {

    /** {@code SELECT ... FOR UPDATE}. Wait bounded by the current transaction's {@code lock_timeout}. */
    Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId, IdempotencyKey key);

    /** {@code INSERT} in {@code IN_PROGRESS} state. Throws if the key is already taken or the wait times out. */
    void insertInProgress(InstitutionId institutionId, IdempotencyKey key, String requestHash,
            Instant createdAt, Instant expiresAt);

    /** {@code UPDATE} of an expired row: new hash, new {@code expires_at}, {@code IN_PROGRESS} state. */
    void restartExpired(InstitutionId institutionId, IdempotencyKey key, String requestHash,
            Instant expiresAt);

    /** {@code UPDATE} of the already-locked row: {@code COMPLETED}, the response, and {@code completed_at}. */
    void complete(InstitutionId institutionId, IdempotencyKey key, IdempotentResponse response,
            Instant completedAt);
}
