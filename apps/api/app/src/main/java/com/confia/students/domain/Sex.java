package com.confia.students.domain;

/** The sex recorded for a student. */
public enum Sex {
    F, M, UNSPECIFIED;

    /** The value with exactly this name; anything else, including {@code null}, is refused. */
    public static Sex parse(String value) {
        if (value != null) {
            for (Sex sex : values()) {
                if (sex.name().equals(value)) {
                    return sex;
                }
            }
        }
        throw new InvalidStudentException();
    }
}
