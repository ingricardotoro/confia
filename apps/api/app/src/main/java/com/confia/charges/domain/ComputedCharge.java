package com.confia.charges.domain;

import com.confia.kernel.Money;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.util.Objects;
import java.util.UUID;

/**
 * A charge the engine has computed and not yet written: who it is for, what produced it (the plan item, the period, the
 * price and the tax validity, all copied into the stored charge) and its four amounts. {@code benefit} is the award that reduced
 * the base and the code of its scholarship, or {@code null} when nothing did; the amount of the benefit is the discount.
 */
public record ComputedCharge(UUID studentId, UUID enrollmentId, UUID planId, UUID planItemId, UUID paymentConceptId,
        String conceptCategory, AccrualPeriod period, UUID priceListItemId, ChargeAmounts amounts, ChargeTax tax, BenefitOffer benefit) {

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
        if ((benefit == null) != amounts.discount().isZero()
                || (benefit != null && !benefit.amount().equals(amounts.discount()))) {
            throw new InvalidChargesValueException();
        }
    }

    /** The base that is left after the benefit and on which the tax is computed. */
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
