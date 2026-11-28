package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The accumulation rule of INV-15 (docs/cortes/scholarships-discounts.md): which benefits reduce a charge and by how much.
 *
 * <ul>
 * <li>When every benefit that applies is stackable (a scholarship counts as stackable; only the greatest scholarship is ever a
 * candidate, see {@link BenefitSelection}) they apply in cascade: the scholarship on the gross base, then each discount on what is
 * left, in the order they come in (percentage before fixed amount, then by code). Every step is computed by the owner of the benefit on
 * the base it is given, rounded {@code HALF_UP} at two decimals, and never above what is left, so the total never takes the base below
 * zero. A step that gives nothing leaves no line.</li>
 * <li>When any discount that applies is not stackable, only the single greatest benefit applies, each one computed on the gross base. On
 * a tie the scholarship goes before the discounts, and between discounts the lowest code does (then the lowest identifier, so the choice
 * never depends on the order they were read in).</li>
 * </ul>
 */
public final class BenefitStacking {

    /** A benefit that applies, what it gives on the gross base and, for a discount, the candidate that computes it again on what is left. */
    private record Step(AppliedBenefit onGross, DiscountCandidate discount) {

        Money amount() {
            return onGross.amount();
        }
    }

    private static final Comparator<Step> GREATEST_FIRST = Comparator
            .comparing(Step::amount, Comparator.reverseOrder())
            .thenComparing(step -> step.onGross().isScholarship() ? 0 : 1)
            .thenComparing(step -> step.onGross().code())
            .thenComparing(step -> step.onGross().isScholarship() ? step.onGross().awardId() : step.onGross().discountId());

    private BenefitStacking() {
    }

    /**
     * @param base the gross base of the charge
     * @param scholarship the greatest scholarship offered on the base (already computed on it), if any
     * @param discounts the discounts in force, in the order of the cascade
     * @return the benefits to apply, in the order they apply; empty when none gives anything
     */
    public static List<AppliedBenefit> apply(Money base, Optional<BenefitOffer> scholarship, List<DiscountCandidate> discounts) {
        boolean stackable = true;
        List<Step> steps = new ArrayList<>();
        scholarship.filter(offer -> offer.amount().isPositive()).ifPresent(offer -> {
            // The scholarship computes its own benefit and never gives more than the base: more than that is a defect, not an input.
            if (offer.amount().isGreaterThan(base)) {
                throw new InvalidChargesValueException();
            }
            steps.add(new Step(AppliedBenefit.of(offer), null));
        });
        for (DiscountCandidate discount : discounts) {
            Money onGross = discount.on(base);
            // A discount that gives nothing on the whole base applies to nothing: it does not stop the others from stacking either.
            if (onGross.isPositive()) {
                stackable &= discount.stackable();
                steps.add(new Step(AppliedBenefit.of(discount, onGross), discount));
            }
        }
        if (steps.isEmpty()) {
            return List.of();
        }
        if (!stackable) {
            return List.of(steps.stream().min(GREATEST_FIRST).orElseThrow().onGross());
        }
        List<AppliedBenefit> cascade = new ArrayList<>();
        Money left = base;
        for (Step step : steps) {
            // The scholarship was computed on the gross base and is the first step; each discount is computed on what is left.
            Money amount = step.discount() == null ? step.amount() : step.discount().on(left);
            if (amount.isGreaterThan(left)) {
                amount = left;
            }
            if (amount.isPositive()) {
                cascade.add(step.discount() == null ? step.onGross() : AppliedBenefit.of(step.discount(), amount));
                left = left.subtract(amount);
            }
        }
        return List.copyOf(cascade);
    }
}
