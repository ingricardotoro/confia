package com.confia.students.application.directory;

import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code students} how many enrollments are active in an
 * academic year. It runs in the transaction of the caller and counts nothing of another institution.
 */
public interface EnrollmentCensus {

    /** The enrollments of the institution whose status is {@code ACTIVE} in the academic year. */
    int activeInYear(InstitutionId institutionId, UUID academicYearId);
}
