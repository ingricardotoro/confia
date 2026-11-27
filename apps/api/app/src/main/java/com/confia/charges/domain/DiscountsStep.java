package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.List;

/**
 * The extension point of the discounts in the pipeline of a charge, next to {@link BenefitsStep}: given the base the price produced, the
 * discounts in force for the student and the concept, already in the order of the cascade (the percentage ones first, then the fixed
 * amount ones, and in each group by code). The identity, {@link #NONE}, offers nothing.
 */
@FunctionalInterface
public interface DiscountsStep {

    /** The discounts in force for a charge whose base is {@code base}, in the order of the cascade; never {@code null}. */
    List<DiscountCandidate> inForceOn(Money base);

    /** The identity: no discount is ever in force. */
    DiscountsStep NONE = base -> List.of();
}
