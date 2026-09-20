package com.confia.architecture.fixture.monetary;

import com.confia.kernel.Money;

/**
 * Permanent, deliberately invalid fixture for {@code MonetaryFloatingPointTest}'s rules 3, 4 and 5
 * (ADR-0004 §Cumplimiento 2; CLAUDE.md, rule 1): the {@link #price} field alone makes this class a
 * "monetary type" under the rules' {@code MONETARY_TYPE} predicate (design.md, decision 3), and
 * each of the three remaining members violates exactly one of the three rules, independently
 * neutralizable to prove none of them is vacuous.
 */
public final class FloatingPointPriceTag {

    /** Present only to make this class monetary; not itself a violation. */
    public Money price;

    /** Violates the field rule: a {@code double} field declared in a monetary type. */
    public double discountRate;

    /** Violates the return-type rule: a {@code double} return declared in a monetary type. */
    public double discountRate() {
        return discountRate;
    }

    /** Violates the parameter rule: a {@code float} parameter declared in a monetary type. */
    public void applyRate(float rate) {
        this.discountRate = rate;
    }
}
