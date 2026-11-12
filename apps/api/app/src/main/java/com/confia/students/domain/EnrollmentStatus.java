package com.confia.students.domain;

/**
 * Where an enrollment stands. It is born {@link #ACTIVE}; from there it can only go to one of the other
 * three, which are final: {@link #WITHDRAWN}, {@link #TRANSFERRED} (to another institution) or {@link
 * #GRADUATED}. A transfer between sections of this institution is not a status: the enrollment stays
 * {@code ACTIVE} in its new section.
 */
public enum EnrollmentStatus {
    ACTIVE, WITHDRAWN, TRANSFERRED, GRADUATED;

    /** The status with exactly this name; anything else, including {@code null}, is refused on {@code status}. */
    public static EnrollmentStatus parse(String value) {
        if (value != null) {
            for (EnrollmentStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
        }
        throw new InvalidEnrollmentException("status", "pattern");
    }
}
