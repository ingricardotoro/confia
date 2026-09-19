package com.confia.kernel;

/**
 * Thrown when an operation combines {@link Money} amounts of two different currencies (for
 * example {@code add}, {@code subtract} or any comparison). Currencies are not personal data, so
 * both are exposed directly (design.md, decision 2).
 */
public final class CurrencyMismatchException extends DomainException {

    public static final String CODE = "currency-mismatch";

    private final CurrencyCode expected;
    private final CurrencyCode actual;

    public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
        super(CODE, "expected currency " + expected + " but got " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public CurrencyCode expected() {
        return expected;
    }

    public CurrencyCode actual() {
        return actual;
    }
}
