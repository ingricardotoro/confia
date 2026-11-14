package com.confia.organization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A section of a grade within an academic year. Its name is unique per year and grade (the database
 * says so), its capacity is optional but above zero, and its year and grade are fixed at creation.
 */
public record Section(UUID id, UUID academicYearId, UUID gradeId, String name, Integer capacity,
        boolean active, Instant createdAt, Instant updatedAt) {

    public Section {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(gradeId, "gradeId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = Names.required(name, Names.MAX_NAME_LENGTH);
        if (capacity != null && capacity <= 0) {
            throw new InvalidAcademicStructureException();
        }
    }

    /** A new section; its year must not be CLOSED and its grade must be active. */
    public static Section create(UUID id, AcademicYear year, Grade grade, String name,
            Integer capacity, Instant now) {
        if (year.isClosed()) {
            throw new AcademicYearClosedException();
        }
        if (!grade.active()) {
            throw new InactiveReferenceException();
        }
        return new Section(id, year.id(), grade.id(), name, capacity, true, now, now);
    }

    /**
     * An edit; the year must still be open. The grade may have been deactivated since: that never
     * blocks an edit of a section already created.
     */
    public Section edit(AcademicYear year, String newName, Integer newCapacity, boolean newActive,
            Instant now) {
        if (year.isClosed()) {
            throw new AcademicYearClosedException();
        }
        return new Section(id, academicYearId, gradeId, newName, newCapacity, newActive, createdAt, now);
    }
}
