package com.confia.kernel;

/**
 * {@link Money}'s three construction and range causes (design.md, decision 2). Every factory
 * deliberately takes no raw input string, which is what structurally guarantees the message never
 * repeats the rejected entry (CLAUDE.md, rule 11): the caller already has it.
 *
 * <p>Precedence when several rules are violated at once (design.md, decision 2): {@link
 * #malformed()} before {@link #scaleExceeded(int)}, and that before {@link #outOfRange()}. The
 * scale is checked before the range because the range is checked on the value already normalized
 * to scale four.
 */
public final class InvalidMoneyAmountException extends DomainException {

    public static final String MALFORMED = "money-amount-malformed";
    public static final String SCALE_EXCEEDED = "money-scale-exceeded";
    public static final String OUT_OF_RANGE = "money-amount-out-of-range";

    private InvalidMoneyAmountException(String code, String message) {
        super(code, message);
    }

    /** The input string is not a plain decimal ({@code -?\d+(\.\d+)?}). */
    static InvalidMoneyAmountException malformed() {
        return new InvalidMoneyAmountException(MALFORMED,
                "amount is not a plain decimal string matching -?\\d+(\\.\\d+)?");
    }

    /** The input has more than four significant decimal digits. */
    static InvalidMoneyAmountException scaleExceeded(int scale) {
        return new InvalidMoneyAmountException(SCALE_EXCEEDED,
                "scale " + scale + " exceeds the maximum of 4");
    }

    /** The absolute value exceeds NUMERIC(14,4)'s limit, 9999999999.9999. */
    static InvalidMoneyAmountException outOfRange() {
        return new InvalidMoneyAmountException(OUT_OF_RANGE,
                "absolute amount exceeds the NUMERIC(14,4) limit of 9999999999.9999");
    }
}
