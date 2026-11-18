package com.confia.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * A tax percentage: greater than zero and at most one hundred, with at most four decimals, always held at scale four so
 * that two equal percentages are equal values whatever the text they came from. It is a percentage and not an amount, so
 * it is not a {@code Money}: nothing here is rounded, and a text with a fifth decimal is refused instead of being cut.
 *
 * <p>No percentage is written anywhere in the code: the values come from the administrator.
 */
public record RatePercent(BigDecimal value) {

    /** The decimals of the column {@code NUMERIC(7,4)}. */
    public static final int SCALE = 4;

    private static final BigDecimal MAXIMUM = BigDecimal.valueOf(100);
    private static final Pattern UNSIGNED_DECIMAL = Pattern.compile("^\\d{1,3}(\\.\\d{1,4})?$");

    public RatePercent {
        if (value == null || value.signum() <= 0 || value.compareTo(MAXIMUM) > 0
                || value.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidCatalogValueException();
        }
        value = value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Reads the text of the API: digits with an optional dot and one to four decimals, no sign, no exponent. */
    public static RatePercent of(String text) {
        if (text == null || !UNSIGNED_DECIMAL.matcher(text).matches()) {
            throw new InvalidCatalogValueException();
        }
        return new RatePercent(new BigDecimal(text));
    }

    /** The percentage with exactly four decimals, as it is stored and shown. */
    public String toPlainString() {
        return value.toPlainString();
    }
}
