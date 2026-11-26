package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** A student has at most one approved award of the same scholarship on any day. Published to clients as the Problem Details code {@code scholarship-award-overlap}. */
public final class AwardOverlapException extends DomainException {

    public static final String CODE = "scholarship-award-overlap";

    public AwardOverlapException() {
        super(CODE, "the student already has an approved award of the scholarship in these days");
    }
}
