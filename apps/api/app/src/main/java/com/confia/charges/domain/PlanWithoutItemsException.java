package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * An activation of a plan that has no item. Published to clients as the Problem Details code {@code
 * plan-without-items}.
 */
public final class PlanWithoutItemsException extends DomainException {

    public static final String CODE = "plan-without-items";

    public PlanWithoutItemsException() {
        super(CODE, "a charge plan without items cannot be activated");
    }
}
