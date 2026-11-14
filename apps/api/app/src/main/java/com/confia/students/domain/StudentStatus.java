package com.confia.students.domain;

/** Whether a student is in use. A student is never deleted: it is made {@code INACTIVE}. */
public enum StudentStatus {
    ACTIVE, INACTIVE;

    /** The status with exactly this name; anything else, including {@code null}, is refused. */
    public static StudentStatus parse(String value) {
        if (value != null) {
            for (StudentStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
        }
        throw new InvalidStudentException();
    }
}
