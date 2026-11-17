package com.confia.charges.domain;

import java.util.regex.Pattern;

/**
 * The one way the aggregates of the charge plans validate a text: a name is stripped of border spaces, not blank, no
 * longer than its column and free of control and format characters. A failure never repeats the rejected text.
 */
final class Names {

    static final int MAX_NAME_LENGTH = 120;
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private Names() {
    }

    static String name(String value) {
        if (value == null) {
            throw new InvalidChargesValueException();
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH
                || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidChargesValueException();
        }
        return stripped;
    }
}
