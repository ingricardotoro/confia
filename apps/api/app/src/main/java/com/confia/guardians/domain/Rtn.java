package com.confia.guardians.domain;

import java.util.regex.Pattern;

/**
 * A Honduran RTN (taxpayer number) in transit: exactly 14 digits once spaces and hyphens are removed, so
 * 0801-1999-123456 and 08011999123456 are the same. It is a fiscal datum: encrypted at rest, never shown, never
 * audited. {@link #toString()} and every failure are redacted.
 */
public final class Rtn {

    private static final Pattern SHAPE = Pattern.compile("[0-9]{14}");

    private final String digits;

    private Rtn(String digits) {
        this.digits = digits;
    }

    public static Rtn of(String raw) {
        if (raw == null) {
            throw new InvalidGuardianException();
        }
        String digits = raw.strip().replace("-", "").replace(" ", "");
        if (!SHAPE.matcher(digits).matches()) {
            throw new InvalidGuardianException();
        }
        return new Rtn(digits);
    }

    /** The 14 digits: the value that is encrypted. */
    public String digits() {
        return digits;
    }

    @Override
    public String toString() {
        return "Rtn[REDACTED]";
    }
}
