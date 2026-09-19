package com.confia.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exact monetary amount, normalized to scale four (coherent with {@code NUMERIC(14,4)}), paired
 * with an explicit {@link CurrencyCode} (CLAUDE.md, rules 1 to 5; ADR-0004; ADR-0011).
 *
 * <p>Every public factory and every operation that returns a new {@code Money} funnels through the
 * single private constructor below, which is where the scale-four and {@code NUMERIC(14,4)} range
 * invariants are enforced without exception (design.md, decision 4).
 */
public final class Money implements Comparable<Money> {

    public static final int SCALE = 4;

    /** NUMERIC(14,4)'s largest representable absolute value. */
    private static final BigDecimal MAX_ABSOLUTE_AMOUNT = new BigDecimal("9999999999.9999");

    /** {@code of(String, CurrencyCode)}'s accepted shape: no exponent, no '+', no separators. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int MAX_STRING_LENGTH = 64;

    private final BigDecimal amount;
    private final CurrencyCode currency;

    private Money(BigDecimal normalizedAmount, CurrencyCode currency) {
        this.currency = Objects.requireNonNull(currency, "currency");
        if (normalizedAmount.abs().compareTo(MAX_ABSOLUTE_AMOUNT) > 0) {
            throw InvalidMoneyAmountException.outOfRange();
        }
        this.amount = normalizedAmount;
    }

    /**
     * Entry point for external decimal strings, such as API input: {@code -?\d+(\.\d+)?}, at most
     * 64 characters. No exponential notation, no leading {@code +}, no spaces, no thousands
     * separator.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#MALFORMED} if the
     *     string does not have that shape; {@link InvalidMoneyAmountException#SCALE_EXCEEDED} if it
     *     has more than four significant decimal digits; {@link
     *     InvalidMoneyAmountException#OUT_OF_RANGE} if the absolute value exceeds
     *     {@code 9999999999.9999}
     */
    public static Money of(String amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.length() > MAX_STRING_LENGTH || !PLAIN_DECIMAL.matcher(amount).matches()) {
            throw InvalidMoneyAmountException.malformed();
        }
        return new Money(normalizeToScale(new BigDecimal(amount)), currency);
    }

    /**
     * Entry point for {@code NUMERIC} values returned by the database driver: accepts any input
     * scale, including negative, because that is exactly what the driver hands back.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#SCALE_EXCEEDED} if it
     *     has more than four significant decimal digits; {@link
     *     InvalidMoneyAmountException#OUT_OF_RANGE} if the absolute value exceeds
     *     {@code 9999999999.9999}
     */
    public static Money of(BigDecimal amount, CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return new Money(normalizeToScale(amount), currency);
    }

    public static Money zero(CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency");
        return new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    /**
     * Reduces to scale four without ever discarding a significant digit: {@link
     * RoundingMode#UNNECESSARY} throws {@link ArithmeticException} the moment that would happen,
     * which this translates to the domain error instead of letting it propagate raw.
     */
    private static BigDecimal normalizeToScale(BigDecimal amount) {
        try {
            return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooManySignificantDigits) {
            throw InvalidMoneyAmountException.scaleExceeded(amount.scale());
        }
    }

    /** Always at scale four. */
    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    /** Exact decimal representation, always at scale four, never in scientific notation. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /**
     * Exact, no rounding involved: adding two scale-four amounts never needs more than scale four
     * (design.md, decision 5). Verifies the {@code NUMERIC(14,4)} range on the result, not only on
     * construction, and constructs no {@code Money} at all if either currency mismatches or the
     * result is out of range.
     *
     * @throws CurrencyMismatchException if {@code other} is a different currency
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#OUT_OF_RANGE} if the
     *     exact result exceeds {@code 9999999999.9999} in absolute value
     */
    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /** Exact, no rounding involved. See {@link #add(Money)} for the shared contract. */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Every financial movement reverses with an opposite-sign entry (CLAUDE.md, rule 5). The
     * {@code NUMERIC(14,4)} range is symmetric, so this can never actually fail from a valid
     * {@code Money}, but it still passes through the same constructor check as every other
     * operation (design.md, decision 4).
     */
    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /**
     * Exact, no rounding involved: multiplying a scale-four amount by an integer factor never
     * needs more than scale four (design.md, decision 5). Verifies the {@code NUMERIC(14,4)}
     * range on the exact result.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#OUT_OF_RANGE} if the
     *     exact result exceeds {@code 9999999999.9999} in absolute value
     */
    public Money multiply(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    /**
     * Multiplies by a decimal factor with a single explicit rounding to scale four (design.md,
     * decision 5). There is deliberately no overload with an implicit rounding mode.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#OUT_OF_RANGE} if the
     *     rounded result exceeds {@code 9999999999.9999} in absolute value
     */
    public Money multiply(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(rounding, "rounding");
        return new Money(amount.multiply(factor).setScale(SCALE, rounding), currency);
    }

    /**
     * Applies a rate expressed in percentage points: {@code amount x percentage / 100}, computed
     * exactly and rounded once, explicitly, to scale four (design.md, decision 6). There is
     * deliberately no overload with an implicit rounding mode.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#OUT_OF_RANGE} if the
     *     rounded result exceeds {@code 9999999999.9999} in absolute value
     */
    public Money percentage(Percentage percentage, RoundingMode rounding) {
        Objects.requireNonNull(percentage, "percentage");
        Objects.requireNonNull(rounding, "rounding");
        BigDecimal exactShare = amount.multiply(percentage.value()).movePointLeft(2);
        return new Money(exactShare.setScale(SCALE, rounding), currency);
    }

    /**
     * Rounds to this currency's minor unit (two decimals for both HNL and USD today) and
     * re-expresses the result at scale four (design.md, decision 4). This is the only point where
     * an amount actually loses precision below scale four, and it always happens explicitly.
     *
     * @throws InvalidMoneyAmountException {@link InvalidMoneyAmountException#OUT_OF_RANGE} if
     *     rounding up carries the result past {@code 9999999999.9999} (for example,
     *     {@code 9999999999.9999} rounded {@code HALF_UP} becomes {@code 10000000000.00})
     */
    public Money roundToMinorUnit(RoundingMode rounding) {
        Objects.requireNonNull(rounding, "rounding");
        BigDecimal roundedToMinorUnit = amount.setScale(currency.minorUnitDigits(), rounding);
        return new Money(roundedToMinorUnit.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /**
     * Compares amounts within the same currency; consistent with {@link #equals(Object)}.
     * Ordering a collection with mixed currencies fails on purpose.
     *
     * @throws CurrencyMismatchException if {@code other} is a different currency
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    /**
     * Compares the normalized amount and the currency; never {@link BigDecimal#equals(Object)} on
     * the raw amount, which would also compare scale (CLAUDE.md, rule 1). Uses {@link
     * BigDecimal#compareTo(BigDecimal)} so equality never depends on how the input was
     * normalized, even though every {@code Money} amount is already at scale four.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money otherMoney)) {
            return false;
        }
        return currency == otherMoney.currency && amount.compareTo(otherMoney.amount) == 0;
    }

    /**
     * Coherent with {@link #equals(Object)}: valid because {@link #amount} is always at scale
     * four, so equal amounts under {@link BigDecimal#compareTo(BigDecimal)} are also equal under
     * {@link BigDecimal#hashCode()}.
     */
    @Override
    public int hashCode() {
        return 31 * amount.hashCode() + currency.hashCode();
    }

    /** For logs and debugging only, for example {@code "1234.5500 HNL"}; the API never uses it. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
