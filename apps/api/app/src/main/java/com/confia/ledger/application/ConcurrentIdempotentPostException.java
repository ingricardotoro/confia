package com.confia.ledger.application;

/** Another transaction committed the same idempotency key first; the caller runs again and replays the winner. */
public final class ConcurrentIdempotentPostException extends RuntimeException {

    public ConcurrentIdempotentPostException() {
        super("the idempotency key was committed by a concurrent transaction");
    }
}
