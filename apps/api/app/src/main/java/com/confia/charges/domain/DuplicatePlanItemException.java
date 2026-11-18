package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * A payment concept that the plan already has: a concept appears once per plan. Published to clients as the Problem
 * Details code {@code duplicate-plan-item}.
 */
public final class DuplicatePlanItemException extends DomainException {

    public static final String CODE = "duplicate-plan-item";

    public DuplicatePlanItemException() {
        super(CODE, "the payment concept is already an item of the charge plan");
    }
}
