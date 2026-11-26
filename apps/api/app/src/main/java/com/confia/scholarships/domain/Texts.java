package com.confia.scholarships.domain;

import java.util.regex.Pattern;

/**
 * The one way the aggregates validate a text. A code is a whitelist: it is shown in lists and used by other cuts, so no space, accent,
 * control or format character and no leading symbol may enter. A name is stripped of border spaces, not blank, no longer than its
 * column and free of control and format characters. A reason is counted in code points like the database and may name a person, so a
 * failure never repeats any text.
 */
final class Texts {

    static final int MAX_NAME_LENGTH = 120;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$");
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private Texts() {
    }

    static String code(String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new InvalidScholarshipsValueException("code", "invalid");
        }
        return value;
    }

    static String name(String value) {
        if (value == null) {
            throw new InvalidScholarshipsValueException("name", "invalid");
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH
                || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidScholarshipsValueException("name", "invalid");
        }
        return stripped;
    }

    static String free(String value, int min, int max, String field) {
        if (value == null) {
            throw new InvalidScholarshipsValueException(field, "invalid");
        }
        String stripped = value.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < min || length > max || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidScholarshipsValueException(field, "invalid");
        }
        return stripped;
    }
}
