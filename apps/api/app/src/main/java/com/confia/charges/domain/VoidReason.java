package com.confia.charges.domain;

import java.util.regex.Pattern;

/**
 * Why a charge is voided: free text of 20 to 500 characters once stripped of border spaces, without control or format
 * characters. The edge validates the same bounds first; this is the second line of defense. A failure never repeats the
 * rejected text, because a reason can mention a person.
 */
public final class VoidReason {

    public static final int MIN_LENGTH = 20;
    public static final int MAX_LENGTH = 500;
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private final String text;

    private VoidReason(String text) {
        this.text = text;
    }

    public static VoidReason of(String value) {
        if (value == null) {
            throw new InvalidChargesValueException();
        }
        String stripped = value.strip();
        if (stripped.length() < MIN_LENGTH || stripped.length() > MAX_LENGTH
                || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidChargesValueException();
        }
        return new VoidReason(stripped);
    }

    public String text() {
        return text;
    }

    /** The reason is free text that may name a person: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "VoidReason[length=" + text.length() + "]";
    }
}
