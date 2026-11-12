package com.confia.students.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What the enrollment rules need to know about a section and its academic year, as the academic structure
 * reports it: this module never imports the domain of {@code organization}, so the use case translates the
 * view of the structure into this value.
 *
 * @param capacity the seats of the section, or {@code null} when it has no limit
 */
public record SectionPlace(UUID sectionId, UUID academicYearId, boolean active, boolean yearClosed,
        LocalDate yearStart, LocalDate yearEnd, Integer capacity) {

    public SectionPlace {
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(yearStart, "yearStart");
        Objects.requireNonNull(yearEnd, "yearEnd");
    }

    /** Whether a section with {@code activeEnrollments} active enrollments can take one more. */
    boolean hasRoom(int activeEnrollments) {
        return capacity == null || activeEnrollments < capacity;
    }

    /** Whether the day is inside the academic year, both ends included. */
    boolean contains(LocalDate day) {
        return !day.isBefore(yearStart) && !day.isAfter(yearEnd);
    }
}
