package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The code is in use by another discount of the institution, whatever its capitals. Published to clients as the Problem Details code {@code duplicate-discount-code}. */
public final class DuplicateDiscountCodeException extends DomainException {

    public static final String CODE = "duplicate-discount-code";

    public DuplicateDiscountCodeException() {
        super(CODE, "the discount code is already in use in the institution");
    }
}
