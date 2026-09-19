package com.confia.kernel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A rate expressed in percentage points ({@code 15} means fifteen percent), normalized to scale
 * four and restricted to the closed range {@code [0, 100]} (design.md, decision 6). {@code
 * Money.percentage(Percentage, RoundingMode)} is the only place a rate applies to an amount;
 * nothing outside {@code kernel} multiplies a raw {@link BigDecimal} against a {@link Money}
 * amount to compute a percentage (CLAUDE.md, rule 3).
 */
public final class Percentage {

    public static final int SCALE = 4;

    private static final BigDecimal MIN_VALUE = BigDecimal.ZERO;
    private static final BigDecimal MAX_VALUE = new BigDecimal("100");

    /** {@code of(String)}'s accepted shape: no exponent, no '+', no separators. */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int MAX_STRING_LENGTH = 64;

    /** Integer digits of {@code 100}: anything with more is out of range. */
    private static final int MAX_INTEGER_DIGITS = 3;

    private final BigDecimal value;

    private Percentage(BigDecimal normalizedValue) {
        if (normalizedValue.compareTo(MIN_VALUE) < 0 || normalizedValue.compareTo(MAX_VALUE) > 0) {
            throw InvalidPercentageException.outOfRange();
        }
        this.value = normalizedValue;
    }

    /**
     * @throws InvalidPercentageException {@link InvalidPercentageException#MALFORMED} if the
     *     string does not have the shape {@code -?\d+(\.\d+)?} or exceeds 64 characters; {@link
     *     InvalidPercentageException#SCALE_EXCEEDED} if it has more than four significant decimal
     *     digits; {@link InvalidPercentageException#OUT_OF_RANGE} if the value falls outside
     *     {@code [0, 100]}
     */
    public static Percentage of(String percentagePoints) {
        Objects.requireNonNull(percentagePoints, "percentagePoints");
        if (percentagePoints.length() > MAX_STRING_LENGTH
                || !PLAIN_DECIMAL.matcher(percentagePoints).matches()) {
            throw InvalidPercentageException.malformed();
        }
        return new Percentage(normalizeToScale(new BigDecimal(percentagePoints)));
    }

    /**
     * @throws InvalidPercentageException {@link InvalidPercentageException#SCALE_EXCEEDED} if it
     *     has more than four significant decimal digits; {@link
     *     InvalidPercentageException#OUT_OF_RANGE} if the value falls outside {@code [0, 100]}
     */
    public static Percentage of(BigDecimal percentagePoints) {
        Objects.requireNonNull(percentagePoints, "percentagePoints");
        return new Percentage(normalizeToScale(requireBoundedScale(percentagePoints)));
    }

    /**
     * Guard for a caller-supplied {@link BigDecimal}, applied before any rescaling (which costs
     * about 10^|scale|): an absurd positive scale is rejected as too many decimals, and a negative
     * scale that can only mean a number too large for the range is rejected without computing it.
     * The string factory needs no such guard: its 64-character limit already bounds the scale.
     */
    private static BigDecimal requireBoundedScale(BigDecimal value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (value.scale() > Money.MAX_INPUT_SCALE) {
            throw InvalidPercentageException.scaleExceeded(value.scale());
        }
        if (value.scale() < 0 && (long) value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw InvalidPercentageException.outOfRange();
        }
        return value;
    }

    /**
     * Reduces to scale four without ever discarding a significant digit: {@link
     * RoundingMode#UNNECESSARY} throws {@link ArithmeticException} the moment that would happen,
     * which this translates to the domain error instead of letting it propagate raw.
     */
    private static BigDecimal normalizeToScale(BigDecimal value) {
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException tooManySignificantDigits) {
            throw InvalidPercentageException.scaleExceeded(value.scale());
        }
    }

    /** Always at scale four, within {@code [0, 100]}. */
    public BigDecimal value() {
        return value;
    }

    public String toPlainString() {
        return value.toPlainString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Percentage otherPercentage)) {
            return false;
        }
        return value.compareTo(otherPercentage.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** For logs and debugging only, for example {@code "15.0000%"}. */
    @Override
    public String toString() {
        return value.toPlainString() + "%";
    }
}
