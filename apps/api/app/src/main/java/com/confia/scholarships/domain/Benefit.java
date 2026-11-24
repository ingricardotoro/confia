package com.confia.scholarships.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InvalidMoneyAmountException;
import com.confia.kernel.InvalidPercentageException;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What a scholarship gives: a percentage of the base of a charge (greater than 0 and up to 100, with at most two decimals) or a fixed
 * amount (greater than 0, in HNL, with at most two decimals). The benefit on one charge is rounded {@code HALF_UP} to the minor unit
 * once, limited to the cap per period when there is one and to the base of the charge, so it never leaves the base below zero
 * (INV-15). All of it is {@link Money} arithmetic and no other class of the system repeats it.
 *
 * @param percent only a percentage benefit has one
 * @param fixedAmount only a fixed amount benefit has one
 */
public record Benefit(BenefitType type, Percentage percent, Money fixedAmount) {

    /** The currency of every scholarship of this cut (other currencies are out of scope). */
    public static final CurrencyCode CURRENCY = CurrencyCode.HNL;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Benefit {
        Objects.requireNonNull(type, "type");
        if ((type == BenefitType.PERCENTAGE) != (percent != null) || (type == BenefitType.FIXED_AMOUNT) != (fixedAmount != null)) {
            throw new InvalidScholarshipsValueException("benefitType", "invalid");
        }
    }

    /**
     * The benefit as the request names it: {@code PERCENTAGE} with only {@code percent}, or {@code FIXED_AMOUNT} with only {@code
     * fixedAmount}.
     */
    public static Benefit of(String type, String percent, String fixedAmount) {
        if (BenefitType.PERCENTAGE.name().equals(type) && percent != null && fixedAmount == null) {
            return percentage(percent);
        }
        if (BenefitType.FIXED_AMOUNT.name().equals(type) && fixedAmount != null && percent == null) {
            return fixed(fixedAmount);
        }
        throw new InvalidScholarshipsValueException("benefitType", "invalid");
    }

    /** A percentage given as the text of the API, in percentage points: greater than 0, up to 100 and with at most two decimals. */
    public static Benefit percentage(String points) {
        Percentage percent;
        try {
            percent = Percentage.of(Objects.requireNonNull(points, "points"));
        } catch (InvalidPercentageException e) {
            throw new InvalidScholarshipsValueException("percent", "invalid");
        }
        BigDecimal value = percent.value();
        if (value.signum() <= 0 || value.compareTo(HUNDRED) > 0 || value.stripTrailingZeros().scale() > 2) {
            throw new InvalidScholarshipsValueException("percent", "invalid");
        }
        return new Benefit(BenefitType.PERCENTAGE, percent, null);
    }

    /** A fixed amount given as the text of the API, in HNL: greater than 0 and with at most two decimals. */
    public static Benefit fixed(String amount) {
        return new Benefit(BenefitType.FIXED_AMOUNT, null, positiveInMinorUnit(amount, "fixedAmount"));
    }

    /** The cap per period given as the text of the API, or {@code null} when there is none: greater than 0, in HNL, two decimals. */
    public static Money cap(String amount) {
        return amount == null ? null : positiveInMinorUnit(amount, "periodCap");
    }

    private static Money positiveInMinorUnit(String amount, String field) {
        Money money;
        try {
            money = Money.of(Objects.requireNonNull(amount, "amount"), CURRENCY);
        } catch (InvalidMoneyAmountException e) {
            throw new InvalidScholarshipsValueException(field, "invalid");
        }
        if (!money.isPositive() || !money.equals(money.roundToMinorUnit(RoundingMode.HALF_UP))) {
            throw new InvalidScholarshipsValueException(field, "invalid");
        }
        return money;
    }

    /**
     * The benefit on a charge whose base is {@code base}: the share or the fixed amount, limited to {@code periodCap} when it is not
     * {@code null} and to the base. A base that is zero or negative gets zero.
     */
    public Money on(Money base, Money periodCap) {
        Objects.requireNonNull(base, "base");
        if (!base.isPositive()) {
            return Money.zero(base.currency());
        }
        Money benefit = type == BenefitType.PERCENTAGE ? base.percentageToMinorUnit(percent, RoundingMode.HALF_UP) : fixedAmount;
        if (periodCap != null && benefit.isGreaterThan(periodCap)) {
            benefit = periodCap;
        }
        return benefit.isGreaterThan(base) ? base : benefit;
    }

    /** Whether the two benefits are the same type and value. */
    public boolean sameAs(Benefit other) {
        return type == other.type && Objects.equals(percent, other.percent) && Objects.equals(fixedAmount, other.fixedAmount);
    }
}
