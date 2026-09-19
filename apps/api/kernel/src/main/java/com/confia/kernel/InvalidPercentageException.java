package com.confia.kernel;

/**
 * {@link Percentage}'s three construction and range causes (design.md, decision 2). Every factory
 * deliberately takes no raw input string, which is what structurally guarantees the message never
 * repeats the rejected entry (CLAUDE.md, rule 11): the caller already has it.
 *
 * <p>Precedence when several rules are violated at once (design.md, decision 2): {@link
 * #malformed()} before {@link #scaleExceeded(int)}, and that before {@link #outOfRange()}.
 */
public final class InvalidPercentageException extends DomainException {

    public static final String MALFORMED = "percentage-malformed";
    public static final String SCALE_EXCEEDED = "percentage-scale-exceeded";
    public static final String OUT_OF_RANGE = "percentage-out-of-range";

    private InvalidPercentageException(String code, String message) {
        super(code, message);
    }

    /** The input string is not a plain decimal ({@code -?\d+(\.\d+)?}). */
    static InvalidPercentageException malformed() {
        return new InvalidPercentageException(MALFORMED,
                "percentage is not a plain decimal string matching -?\\d+(\\.\\d+)?");
    }

    /** The input has more than four significant decimal digits. */
    static InvalidPercentageException scaleExceeded(int scale) {
        return new InvalidPercentageException(SCALE_EXCEEDED,
                "scale " + scale + " exceeds the maximum of 4");
    }

    /** The value is outside the closed range [0, 100]. */
    static InvalidPercentageException outOfRange() {
        return new InvalidPercentageException(OUT_OF_RANGE,
                "percentage must be within the closed range [0, 100]");
    }
}
