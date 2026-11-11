package com.confia.organization.domain;

/**
 * The one way the aggregates of the academic structure validate a text: stripped of border spaces, not
 * blank and no longer than the column that stores it. A failure never repeats the rejected text.
 */
final class Names {

    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_CODE_LENGTH = 30;

    private Names() {
    }

    static String required(String value, int maxLength) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > maxLength) {
            throw new InvalidAcademicStructureException();
        }
        return value.strip();
    }
}
