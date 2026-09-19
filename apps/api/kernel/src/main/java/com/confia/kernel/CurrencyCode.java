package com.confia.kernel;

/**
 * The closed set of currencies CONFIA is enabled to operate with (proposal, technical decision 1;
 * design.md, decision 3), mirroring the {@code CHECK (currency IN ('HNL','USD'))} constraint on
 * every money column (ADR-0004, ADR-0011). Adding a currency is deliberate: one new enum constant
 * plus the matching database constraint, never a code path that accepts an arbitrary ISO 4217
 * code.
 */
public enum CurrencyCode {

    HNL(2),
    USD(2);

    private final int minorUnitDigits;

    CurrencyCode(int minorUnitDigits) {
        this.minorUnitDigits = minorUnitDigits;
    }

    /** Number of decimal digits of this currency's minor unit (2 for both HNL and USD today). */
    public int minorUnitDigits() {
        return minorUnitDigits;
    }

    /**
     * Resolves an ISO 4217 alphabetic code to an enabled {@link CurrencyCode}. Case-sensitive on
     * purpose: ISO 4217 codes are always uppercase, and silently tolerating another case would hide
     * an integration defect.
     *
     * @throws UnsupportedCurrencyException if {@code isoCode} is outside the enabled set
     */
    public static CurrencyCode fromIsoCode(String isoCode) {
        for (CurrencyCode candidate : values()) {
            if (candidate.name().equals(isoCode)) {
                return candidate;
            }
        }
        throw new UnsupportedCurrencyException();
    }
}
