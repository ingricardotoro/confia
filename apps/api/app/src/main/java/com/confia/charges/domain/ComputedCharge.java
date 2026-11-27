package com.confia.charges.domain;

import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A charge the engine has computed and not yet written: who it is for, what produced it (the plan item, the period, the
 * price and the tax validity, all copied into the stored charge) and its four amounts. {@code benefits} are the benefits that reduced
 * the base, in the order they were applied (the scholarship first, then the discounts of the cascade), and the discount of the amounts is
 * their sum; the list is empty when nothing reduced the base.
 */
public record ComputedCharge(UUID studentId, UUID enrollmentId, UUID planId, UUID planItemId, UUID paymentConceptId,
        String conceptCategory, AccrualPeriod period, UUID priceListItemId, ChargeAmounts amounts, ChargeTax tax,
        List<AppliedBenefit> benefits) {

    public ComputedCharge {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(enrollmentId, "enrollmentId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planItemId, "planItemId");
        Objects.requireNonNull(paymentConceptId, "paymentConceptId");
        Objects.requireNonNull(conceptCategory, "conceptCategory");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(priceListItemId, "priceListItemId");
        Objects.requireNonNull(amounts, "amounts");
        Objects.requireNonNull(tax, "tax");
        benefits = List.copyOf(Objects.requireNonNull(benefits, "benefits"));
        Money sum = Money.zero(amounts.discount().currency());
        for (AppliedBenefit benefit : benefits) {
            if (benefit.amount().currency() != sum.currency()) {
                throw new InvalidChargesValueException();
            }
            sum = sum.add(benefit.amount());
        }
        if (!sum.equals(amounts.discount()) || benefits.stream().filter(AppliedBenefit::isScholarship).count() > 1) {
            throw new InvalidChargesValueException();
        }
    }

    /** The scholarship that reduced the charge, as the offer it came from, or {@code null} when none did. */
    public BenefitOffer benefit() {
        return benefits.stream().filter(AppliedBenefit::isScholarship).findFirst().map(AppliedBenefit::asOffer).orElse(null);
    }

    /** The base that is left after the benefits and on which the tax is computed. */
    public Money netBase() {
        return amounts.base().subtract(amounts.discount());
    }

    public boolean taxable() {
        return tax.taxable();
    }

    public UUID taxRateId() {
        return tax.taxRateId();
    }

    public UUID taxRatePeriodId() {
        return tax.taxRatePeriodId();
    }

    public Percentage taxPercent() {
        return tax.percent();
    }
}
