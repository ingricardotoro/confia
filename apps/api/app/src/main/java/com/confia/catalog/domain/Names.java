package com.confia.catalog.domain;

import java.util.regex.Pattern;

/**
 * The one way the aggregates of the catalog validate a text. A code is a whitelist ({@link #CODE_PATTERN}): it is used
 * as an identifier by other cuts and shown in lists, so no space, accent, control or format character (zero-width,
 * right-to-left override) and no leading symbol (such as {@code =}) may enter. A name is stripped of border spaces, not
 * blank, no longer than its column and free of control and format characters. A failure never repeats the rejected text.
 */
final class Names {

    static final int MAX_NAME_LENGTH = 120;
    static final String CODE_REGEX = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE_REGEX);
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private Names() {
    }

    static String code(String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw new InvalidCatalogValueException();
        }
        return value;
    }

    static String name(String value) {
        if (value == null) {
            throw new InvalidCatalogValueException();
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME_LENGTH
                || CONTROL_OR_FORMAT.matcher(stripped).find()) {
            throw new InvalidCatalogValueException();
        }
        return stripped;
    }
}
