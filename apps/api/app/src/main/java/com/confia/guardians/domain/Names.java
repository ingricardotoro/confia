package com.confia.guardians.domain;

/**
 * The one way the registry validates a text: stripped of border spaces, not blank and no longer than the
 * column that stores it. A failure never repeats the rejected text.
 */
final class Names {

    static final int MAX_NAME_LENGTH = 100;

    private Names() {
    }

    static String required(String value, int maxLength) {
        if (value == null) {
            throw new InvalidGuardianException();
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            throw new InvalidGuardianException();
        }
        return stripped;
    }

    /** An optional text: blank or missing is absent, otherwise stripped and within the length. */
    static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return required(value, maxLength);
    }
}
