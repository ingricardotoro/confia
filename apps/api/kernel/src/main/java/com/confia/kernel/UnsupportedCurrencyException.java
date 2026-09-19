package com.confia.kernel;

/**
 * An ISO 4217 code was requested that is outside {@link CurrencyCode}'s closed, enabled set.
 * Mirrors the {@code CHECK (currency IN ('HNL','USD'))} constraint (ADR-0004, ADR-0011). The
 * message never echoes the rejected input (CLAUDE.md, rule 11): the caller already has it.
 */
public final class UnsupportedCurrencyException extends DomainException {

    public static final String CODE = "currency-unsupported";

    public UnsupportedCurrencyException() {
        super(CODE, "the requested ISO 4217 code is outside the enabled currency set");
    }
}
