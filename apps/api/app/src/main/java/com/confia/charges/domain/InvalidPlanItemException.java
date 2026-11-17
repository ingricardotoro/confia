package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * A plan item that cannot produce periods: a one-time plan whose item asks for more than one period. Published to clients as
 * the Problem Details code {@code plan-item-invalid}.
 */
public final class InvalidPlanItemException extends DomainException {

    public static final String CODE = "plan-item-invalid";

    public InvalidPlanItemException() {
        super(CODE, "a one-time charge plan accrues exactly one period per item");
    }
}
