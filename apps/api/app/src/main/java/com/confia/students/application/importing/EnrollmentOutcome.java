package com.confia.students.application.importing;

/** What happened to a row handed to {@link EnrollmentImportGateway#applyAll}. */
public enum EnrollmentOutcome {
    /** The student was enrolled. */
    CREATED,
    /** The student already has an active enrollment in this same section (earlier or concurrent application): skipped. */
    ALREADY_IMPORTED,
    /** The student has an active enrollment in another section of the year: the import never moves or replaces it. */
    DUPLICATE_ACTIVE_ENROLLMENT,
    /** The section filled up before the row was reached. */
    SECTION_FULL,
    /** The academic year is closed (it changed after the judgment). */
    ACADEMIC_YEAR_CLOSED,
    /** The section is not active (it changed after the judgment). */
    INACTIVE_SECTION,
    /** The student is not active (it changed after the judgment). */
    STUDENT_NOT_ACTIVE,
    /** The date is outside the year or in the future. */
    DATE_OUT_OF_RANGE
}
