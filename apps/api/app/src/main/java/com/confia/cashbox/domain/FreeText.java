package com.confia.cashbox.domain;

import java.util.regex.Pattern;

/**
 * Free text of a cash record (a note, the reason of a difference): stripped of border spaces, counted in code points like the
 * database, without control or format characters. It may name a person, so a failure never repeats it.
 */
final class FreeText {

    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private FreeText() {
    }

    /** The stripped text when it has between {@code min} and {@code max} code points; a validation error otherwise. */
    static String of(String value, int min, int max) {
        if (value == null) {
            throw new InvalidCashboxValueException();
        }
        String stripped = value.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < min || length > max || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidCashboxValueException();
        }
        return stripped;
    }
}
