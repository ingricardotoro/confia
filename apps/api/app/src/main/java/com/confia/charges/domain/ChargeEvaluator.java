package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The pure rules of one charge (docs/cortes/charges-accrual.md, engine points 3 to 6): given the student, the period of a plan
 * item, the terms of the concept, its prices and its tax rate, it answers the charge or refuses it with the {@link
 * AccrualErrorCode}. One date, the pricing date (the first day of the period), decides both the price and the tax rate. The
 * pipeline is {@code BasePrice -> BenefitsStep -> TaxableBase -> Tax -> Total}: the benefits step offers what the awards in force
 * give on the base (the greatest scholarship is the only one that is ever a candidate) and the discounts step what the discounts in
 * force give; {@link BenefitStacking} decides which apply and by how much (INV-15: in cascade when all are stackable, otherwise only
 * the greatest, and never above the base), and the tax of a taxable concept is computed on the base that is left.
 */
public final class ChargeEvaluator {

    private final BenefitsStep benefits;
    private final DiscountsStep discounts;

    /** The evaluator of a charge that only a scholarship can reduce. */
    public ChargeEvaluator(BenefitsStep benefits) {
        this(benefits, DiscountsStep.NONE);
    }

    public ChargeEvaluator(BenefitsStep benefits, DiscountsStep discounts) {
        this.benefits = Objects.requireNonNull(benefits, "benefits");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
    }

    /** The evaluator of a charge that no award can reduce. */
    public static ChargeEvaluator withoutBenefits() {
        return new ChargeEvaluator(BenefitsStep.NONE);
    }

    /**
     * @param prices the prices of the concept in the active price lists of the academic year of the plan
     * @param rate the tax rate of the concept with its validities, or {@code null} when the concept has none
     * @throws AccrualFailure for any rule that refuses the charge
     */
    public ComputedCharge evaluate(StudentPlacement student, java.util.UUID planId, AccrualPeriods.PlanPeriod planPeriod,
            ConceptTerms concept, List<PriceCandidate> prices, TaxRateInfo rate) {
        Objects.requireNonNull(student, "student");
        Objects.requireNonNull(planPeriod, "planPeriod");
        Objects.requireNonNull(concept, "concept");
        if (!student.active()) {
            throw new AccrualFailure(AccrualErrorCode.STUDENT_INACTIVE);
        }
        if (!concept.active()) {
            throw new AccrualFailure(AccrualErrorCode.CONCEPT_INACTIVE);
        }
        AccrualPeriod period = planPeriod.period();
        PriceResolver.ResolvedPrice price = PriceResolver.resolve(prices, student.gradeId(), student.modalityId(),
                period.start());
        Money base = price.amount();
        Optional<BenefitOffer> scholarship = BenefitSelection.best(benefits.offersOn(base), base.currency());
        List<AppliedBenefit> applied = BenefitStacking.apply(base, scholarship, discounts.inForceOn(base));
        Money discount = Money.zero(base.currency());
        for (AppliedBenefit benefit : applied) {
            discount = discount.add(benefit.amount());
        }
        if (discount.isNegative() || discount.isGreaterThan(base)) {
            throw new InvalidChargesValueException();
        }
        Money taxableBase = base.subtract(discount);
        ChargeTax tax = concept.taxable() ? ChargeTax.taxable(rate, period.start(), taxableBase)
                : ChargeTax.none(base.currency());
        ChargeAmounts amounts = ChargeAmounts.of(base, discount, tax.amount());
        return new ComputedCharge(student.studentId(), student.enrollmentId(), planId, planPeriod.planItemId(),
                concept.conceptId(), concept.category(), period, price.priceListItemId(), amounts, tax, applied);
    }
}
