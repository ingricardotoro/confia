package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code ledger-idempotency-conflict}. */
public final class LedgerIdempotencyConflictException extends DomainException {

    public static final String CODE = "ledger-idempotency-conflict";

    public LedgerIdempotencyConflictException() {
        super(CODE, "the idempotency key was used with another content");
    }
}
