package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The type or the value of the benefit of a discount in use (an assignment of it was approved): another discount is created. Published to clients as the Problem Details code {@code discount-benefit-locked}. */
public final class DiscountBenefitLockedException extends DomainException {

    public static final String CODE = "discount-benefit-locked";

    public DiscountBenefitLockedException() {
        super(CODE, "the benefit of a discount in use does not change");
    }
}
