package com.confia.kernel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    /**
     * Largest |scale| accepted from a caller-supplied {@link BigDecimal} (the precision of {@link
     * java.math.MathContext#DECIMAL128}). Rescaling costs about 10^|scale|, so a value such as
     * {@code 1E-500000000} would hang the calling thread; it is rejected before any rescaling. A
     * value with more than 34 decimals is rejected even if they are all zeros: that is the price of
     * never inspecting its digits (design.md, decision 4).
     */
    static final int MAX_INPUT_SCALE = 34;

    /** Integer digits of {@code 9999999999.9999}: anything with more is out of range. */
    private static final int MAX_INTEGER_DIGITS = 10;

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
        return new Money(normalizeToScale(requireBoundedScale(amount)), currency);
    }

    public static Money zero(CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency");
        return new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    /**
     * Guard for a caller-supplied {@link BigDecimal}, applied before any rescaling (which costs
     * about 10^|scale|): an absurd positive scale is rejected as too many decimals, and a negative
     * scale that can only mean a number too large for the range is rejected without computing it.
     * The string factory needs no such guard: its 64-character limit already bounds the scale.
     */
    private static BigDecimal requireBoundedScale(BigDecimal amount) {
        if (amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (amount.scale() > MAX_INPUT_SCALE) {
            throw InvalidMoneyAmountException.scaleExceeded(amount.scale());
        }
        if (amount.scale() < 0 && (long) amount.precision() - amount.scale() > MAX_INTEGER_DIGITS) {
            throw InvalidMoneyAmountException.outOfRange();
        }
        return amount;
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
        if (Math.abs((long) factor.scale()) > MAX_INPUT_SCALE) {
            // A factor comes from code, not from a user: an absurd scale is a programming error.
            throw new IllegalArgumentException(
                    "factor scale must be within +/-" + MAX_INPUT_SCALE + ", was " + factor.scale());
        }
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

    /**
     * Distributes this total proportionally to {@code ratios}, in the currency's minor unit,
     * using the largest-remainder method: no division ever silently discards the remainder
     * (design.md, decision 7). Ties on the remainder favor the lower index, matching the business
     * expectation that the first installments absorb the extra cent. The sum of the returned
     * parts always equals this total exactly.
     *
     * @throws IllegalArgumentException if {@code ratios} is empty, contains a negative weight, is
     *     entirely zero, or if this amount is not an exact multiple of the currency's minor unit
     *     (a programming error, design.md decision 7)
     */
    public List<Money> allocate(int... ratios) {
        Objects.requireNonNull(ratios, "ratios");
        long weightSum = requireValidRatios(ratios);

        int minorUnitDigits = currency.minorUnitDigits();
        BigInteger totalMinorUnits = requireExactMinorUnitMultiple(minorUnitDigits);
        BigInteger[] minorUnitParts =
                distributeByLargestRemainder(totalMinorUnits, ratios, weightSum);

        boolean negative = amount.signum() == -1;
        List<Money> parts = new ArrayList<>(ratios.length);
        for (BigInteger part : minorUnitParts) {
            BigInteger signedPart = negative ? part.negate() : part;
            parts.add(Money.of(new BigDecimal(signedPart, minorUnitDigits), currency));
        }
        return List.copyOf(parts);
    }

    /** @return the sum of {@code ratios}, guaranteed positive */
    private static long requireValidRatios(int[] ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("ratios must not be empty");
        }
        long weightSum = 0;
        for (int ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("ratios must not contain a negative weight");
            }
            weightSum += ratio;
        }
        if (weightSum <= 0) {
            throw new IllegalArgumentException("ratios must not be all zero");
        }
        return weightSum;
    }

    /** @return this amount's absolute value, expressed as an exact count of minor units */
    private BigInteger requireExactMinorUnitMultiple(int minorUnitDigits) {
        BigInteger minorUnitFactor = BigInteger.TEN.pow(SCALE - minorUnitDigits);
        BigInteger unscaledAmount = amount.unscaledValue().abs();
        if (unscaledAmount.remainder(minorUnitFactor).signum() != 0) {
            throw new IllegalArgumentException(
                    "amount must be an exact multiple of the currency's minor unit to allocate");
        }
        return unscaledAmount.divide(minorUnitFactor);
    }

    /**
     * Largest-remainder method (design.md, decision 7): each weight's exact quotient and
     * remainder in minor units, then the leftover units go one by one to the highest remainders,
     * lower index first on a tie.
     *
     * @return one non-negative part per ratio, summing exactly to {@code totalMinorUnits}
     */
    private static BigInteger[] distributeByLargestRemainder(
            BigInteger totalMinorUnits, int[] ratios, long weightSum) {
        BigInteger weightSumAsBigInteger = BigInteger.valueOf(weightSum);
        int partCount = ratios.length;
        BigInteger[] quotients = new BigInteger[partCount];
        BigInteger[] remainders = new BigInteger[partCount];
        BigInteger distributed = BigInteger.ZERO;
        for (int i = 0; i < partCount; i++) {
            BigInteger numerator = totalMinorUnits.multiply(BigInteger.valueOf(ratios[i]));
            BigInteger[] divideAndRemainder = numerator.divideAndRemainder(weightSumAsBigInteger);
            quotients[i] = divideAndRemainder[0];
            remainders[i] = divideAndRemainder[1];
            distributed = distributed.add(quotients[i]);
        }

        List<Integer> indicesByRemainderDescendingThenIndexAscending = new ArrayList<>();
        for (int i = 0; i < partCount; i++) {
            indicesByRemainderDescendingThenIndexAscending.add(i);
        }
        indicesByRemainderDescendingThenIndexAscending.sort(
                Comparator.<Integer, BigInteger>comparing(i -> remainders[i]).reversed()
                        .thenComparingInt(Integer::intValue));

        int leftover = totalMinorUnits.subtract(distributed).intValueExact();
        for (int i = 0; i < leftover; i++) {
            int index = indicesByRemainderDescendingThenIndexAscending.get(i);
            quotients[index] = quotients[index].add(BigInteger.ONE);
        }
        return quotients;
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
