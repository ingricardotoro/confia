package com.confia.charges.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A student of the population of a period: the enrollment that qualifies it, its grade and the modality of the grade at the
 * reference date, and whether the student record is active. The grade decides the price list level.
 */
public record StudentPlacement(UUID studentId, UUID enrollmentId, UUID gradeId, UUID modalityId, boolean active) {

    public StudentPlacement {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(enrollmentId, "enrollmentId");
        Objects.requireNonNull(gradeId, "gradeId");
        Objects.requireNonNull(modalityId, "modalityId");
    }
}
