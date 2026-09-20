package com.confia.architecture.fixture.monetary;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Permanent, deliberately invalid fixture for {@code MonetaryFloatingPointTest}'s
 * {@code NO_BIG_DECIMAL_FROM_FLOATING_POINT} rule (ADR-0004 §Cumplimiento 2; CLAUDE.md, rule 1).
 * Each of the three prohibited floating-point {@link BigDecimal} constructions lives in its own
 * method, so neutralizing any one of them independently proves the fixture is not vacuously
 * rejected (design.md, decision 3).
 */
public final class FloatingPointBigDecimal {

    private FloatingPointBigDecimal() {
    }

    /** Violates the rule: {@code new BigDecimal(double)} rounds the binary value first. */
    public static BigDecimal fromDoubleConstructor() {
        return new BigDecimal(0.1);
    }

    /** Violates the rule: same problem, with an explicit {@link MathContext}. */
    public static BigDecimal fromDoubleAndMathContextConstructor() {
        return new BigDecimal(0.1, MathContext.DECIMAL64);
    }

    /** Violates the rule: {@code BigDecimal.valueOf(double)} has the identical defect. */
    public static BigDecimal fromValueOf() {
        return BigDecimal.valueOf(0.1);
    }
}
