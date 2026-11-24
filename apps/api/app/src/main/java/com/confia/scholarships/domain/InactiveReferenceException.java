package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** A scholarship or a payment concept that is deactivated cannot be used for what is new. Published to clients as the Problem Details code {@code inactive-reference}. */
public final class InactiveReferenceException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveReferenceException() {
        super(CODE, "the referenced row is inactive");
    }
}
