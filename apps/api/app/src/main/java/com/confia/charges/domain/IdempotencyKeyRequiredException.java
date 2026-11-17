package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * The request of an accrual run came without its {@code Idempotency-Key}. Published to clients as the Problem Details code
 * {@code idempotency-key-missing}.
 */
public final class IdempotencyKeyRequiredException extends DomainException {

    public static final String CODE = "idempotency-key-missing";

    public IdempotencyKeyRequiredException() {
        super(CODE, "a request that queues an accrual run carries an Idempotency-Key");
    }
}
