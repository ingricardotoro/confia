package com.confia.organization.application.directory;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A section with the year, the grade and the modality it belongs to, as the other modules may see it.
 *
 * @param capacity the seats of the section, or {@code null} when it has no limit
 * @param academicYearStatus {@code PLANNED}, {@code ACTIVE} or {@code CLOSED}
 * @param startDate the first day of the academic year, included
 * @param endDate the last day of the academic year, included
 */
public record SectionRef(UUID sectionId, String name, boolean active, Integer capacity, UUID academicYearId,
        String academicYearName, String academicYearStatus, LocalDate startDate, LocalDate endDate, UUID gradeId,
        String gradeName, String modalityName) {

    public SectionRef {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(academicYearName, "academicYearName");
        Objects.requireNonNull(academicYearStatus, "academicYearStatus");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        Objects.requireNonNull(gradeId, "gradeId");
        Objects.requireNonNull(gradeName, "gradeName");
        Objects.requireNonNull(modalityName, "modalityName");
    }

    /** Whether the academic year of the section is closed. */
    public boolean academicYearClosed() {
        return "CLOSED".equals(academicYearStatus);
    }
}
