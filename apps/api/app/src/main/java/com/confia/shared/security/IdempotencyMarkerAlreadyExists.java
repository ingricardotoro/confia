package com.confia.shared.security;

/**
 * Internal signal that {@link IdempotencyRecordStore#insertInProgress} collided with a marker row
 * for the same primary key that a concurrent transaction already committed ({@code SQLState
 * 23505}; design.md, decision 5). Not a {@link com.confia.kernel.DomainException}: it carries no
 * stable, web-facing error code because it never survives past this component's own boundary — the
 * future {@code IdempotentExecutor} (design.md, decision 6) catches it and turns it into either a
 * replayed response or an {@link IdempotencyPayloadMismatchException}, so no caller outside {@code
 * com.confia.shared.security} ever observes this type.
 */
public final class IdempotencyMarkerAlreadyExists extends RuntimeException {

    public IdempotencyMarkerAlreadyExists(Throwable cause) {
        super("idempotency marker already exists for this primary key", cause);
    }
}
