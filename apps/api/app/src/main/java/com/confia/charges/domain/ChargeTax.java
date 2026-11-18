package com.confia.charges.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The tax of one charge: the amount and, when the concept is taxable, the rate, the validity and the percentage that
 * produced it (the copies a charge stores). A non-taxable charge has zero tax and none of the references.
 */
public record ChargeTax(Money amount, UUID taxRateId, UUID taxRatePeriodId, Percentage percent) {

    public ChargeTax {
        Objects.requireNonNull(amount, "amount");
        boolean taxable = taxRateId != null;
        if (taxable != (taxRatePeriodId != null) || taxable != (percent != null) || (!taxable && !amount.isZero())) {
            throw new InvalidChargesValueException();
        }
    }

    public boolean taxable() {
        return taxRateId != null;
    }

    /** No tax: a concept that is not taxable. */
    public static ChargeTax none(CurrencyCode currency) {
        return new ChargeTax(Money.zero(currency), null, null, null);
    }

    /**
     * The tax of a taxable concept at the pricing date (docs/cortes/charges-accrual.md, engine points 5 and 6): the rate
     * must be assigned, active and in force at the date, and the tax is the taxable base times the percentage, rounded
     * ONCE, HALF_UP, to the minor unit. Any other case is an error of the charge and never a rate of zero.
     *
     * @param rate the rate of the concept, or {@code null} when it has none
     * @param taxableBase the base minus the discount
     * @throws AccrualFailure {@code TAX_RATE_MISSING}, {@code TAX_RATE_INACTIVE} or {@code TAX_RATE_NOT_IN_FORCE}
     */
    public static ChargeTax taxable(TaxRateInfo rate, LocalDate pricingDate, Money taxableBase) {
        if (rate == null) {
            throw new AccrualFailure(AccrualErrorCode.TAX_RATE_MISSING);
        }
        if (!rate.active()) {
            throw new AccrualFailure(AccrualErrorCode.TAX_RATE_INACTIVE);
        }
        TaxRateInfo.Validity validity = rate.validities().stream().filter(v -> v.covers(pricingDate)).findFirst()
                .orElseThrow(() -> new AccrualFailure(AccrualErrorCode.TAX_RATE_NOT_IN_FORCE));
        Money tax = taxableBase.percentageToMinorUnit(validity.percent(), RoundingMode.HALF_UP);
        return new ChargeTax(tax, rate.taxRateId(), validity.periodId(), validity.percent());
    }
}
