package com.confia.students.application.importing;

/**
 * One line of an enrollment file, every cell as it was read: the student by the reference it has in the source system,
 * the section by the names of its academic year, modality, grade and section, and the day of the enrollment. The record
 * keeps personal references of minors, so it prints only its line.
 */
public record EnrollmentImportRow(int line, String studentLegacyId, String academicYear, String modality,
        String grade, String section, String enrolledOn) {

    /** Nothing of the cells reaches a log through this method. */
    @Override
    public String toString() {
        return "EnrollmentImportRow[line=" + line + "]";
    }
}
