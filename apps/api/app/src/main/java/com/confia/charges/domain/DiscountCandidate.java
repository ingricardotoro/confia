package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A discount in force for a charge, as {@code scholarships} describes it to this module: who it is (identifier, code and, for an
 * agreement, the approved assignment of the student), whether it is stackable and the function that gives its benefit on a base (the
 * share or the fixed amount, rounded {@code HALF_UP} once at two decimals and never above the base it is asked on; the scholarships
 * capability owns that rule). This module only decides how the candidates stack ({@link BenefitStacking}).
 */
public record DiscountCandidate(UUID discountId, String discountCode, UUID assignmentId, boolean stackable,
        UnaryOperator<Money> benefit) {

    public DiscountCandidate {
        Objects.requireNonNull(discountId, "discountId");
        Objects.requireNonNull(discountCode, "discountCode");
        Objects.requireNonNull(benefit, "benefit");
        if (discountCode.isBlank()) {
            throw new InvalidChargesValueException();
        }
    }

    /** What this discount gives on {@code base}; a function that answers more than the base is a defect, never a benefit. */
    public Money on(Money base) {
        Money given = benefit.apply(Objects.requireNonNull(base, "base"));
        if (given == null || given.currency() != base.currency() || given.isNegative() || given.isGreaterThan(base)) {
            throw new InvalidChargesValueException();
        }
        return given;
    }
}
