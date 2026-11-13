package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.directory.EnrollmentCensus;
import java.util.Objects;
import java.util.UUID;

/** The census {@code students} publishes to other modules, over the port of the enrollments. It only reads. */
public final class EnrollmentCensusReader implements EnrollmentCensus {

    private final EnrollmentRepository enrollments;

    public EnrollmentCensusReader(EnrollmentRepository enrollments) {
        this.enrollments = Objects.requireNonNull(enrollments, "enrollments");
    }

    @Override
    public int activeInYear(InstitutionId institutionId, UUID academicYearId) {
        return enrollments.countActiveInYear(Objects.requireNonNull(institutionId, "institutionId"),
                Objects.requireNonNull(academicYearId, "academicYearId"));
    }
}
