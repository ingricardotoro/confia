package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** A student has at most one approved assignment of the same discount on any day. Published to clients as the Problem Details code {@code discount-assignment-overlap}. */
public final class DiscountAssignmentOverlapException extends DomainException {

    public static final String CODE = "discount-assignment-overlap";

    public DiscountAssignmentOverlapException() {
        super(CODE, "the student already has an approved assignment of the discount in these days");
    }
}
