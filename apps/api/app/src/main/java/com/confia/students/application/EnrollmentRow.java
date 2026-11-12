package com.confia.students.application;

import com.confia.students.domain.Enrollment;

/**
 * An enrollment with the code and the names of its student, as the list queries read them in one join of the
 * module's own tables. The names are personal data of a minor: they reach the view and never a log.
 */
public record EnrollmentRow(Enrollment enrollment, String studentCode, String studentFirstName,
        String studentLastName) {

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "EnrollmentRow[id=" + enrollment.id() + "]";
    }
}
