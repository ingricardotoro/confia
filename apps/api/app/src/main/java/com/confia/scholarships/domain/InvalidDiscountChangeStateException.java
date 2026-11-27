package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** Approving or rejecting needs a campaign discount that waits for a decision. Published to clients as the Problem Details code {@code invalid-discount-change-state}. */
public final class InvalidDiscountChangeStateException extends DomainException {

    public static final String CODE = "invalid-discount-change-state";

    public InvalidDiscountChangeStateException() {
        super(CODE, "the discount does not wait for a decision");
    }
}
