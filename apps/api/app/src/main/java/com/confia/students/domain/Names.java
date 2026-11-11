package com.confia.students.domain;

/**
 * The one way the registry validates a text: stripped of border spaces, not blank and no longer than the
 * column that stores it. A failure never repeats the rejected text.
 */
final class Names {

    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_CODE_LENGTH = 30;

    private Names() {
    }

    static String required(String value, int maxLength) {
        if (value == null) {
            throw new InvalidStudentException();
        }
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > maxLength) {
            throw new InvalidStudentException();
        }
        return stripped;
    }
}
