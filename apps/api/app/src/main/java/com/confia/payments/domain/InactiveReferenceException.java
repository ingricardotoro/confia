package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** An account of the chart that is deactivated. Published to clients as the Problem Details code {@code inactive-reference}. */
public final class InactiveReferenceException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveReferenceException() {
        super(CODE, "the referenced resource is inactive");
    }
}
