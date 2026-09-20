package com.confia.architecture.fixture.monetary;

import java.math.BigDecimal;

/**
 * Permanent, deliberately invalid fixture for {@code MonetaryFloatingPointTest}'s
 * {@code NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY} rule (ADR-0004 §Cumplimiento 2; CLAUDE.md, rule 1):
 * {@link BigDecimal#equals(Object)} also compares scale, so {@code 1.0} and {@code 1.00} come out
 * unequal. Every monetary comparison outside {@code Money} must go through {@link
 * BigDecimal#compareTo(BigDecimal)} instead.
 */
public final class RawBigDecimalComparison {

    private RawBigDecimalComparison() {
    }

    /** Violates the rule: {@code left} has static type {@link BigDecimal}. */
    public static boolean rawEquals(BigDecimal left, BigDecimal right) {
        return left.equals(right);
    }
}
