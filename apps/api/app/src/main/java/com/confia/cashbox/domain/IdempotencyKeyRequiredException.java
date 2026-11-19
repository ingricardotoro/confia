package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The request came without its Idempotency-Key. Published to clients as the Problem Details code {@code idempotency-key-missing}. */
public final class IdempotencyKeyRequiredException extends DomainException {

    public static final String CODE = "idempotency-key-missing";

    public IdempotencyKeyRequiredException() {
        super(CODE, "a request that writes a cash session carries an idempotency key");
    }
}
