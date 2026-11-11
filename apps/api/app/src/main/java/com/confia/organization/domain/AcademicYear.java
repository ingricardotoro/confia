package com.confia.organization.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An academic year of an institution (docs/cortes/organization-academic-structure.md). Immutable: an
 * edit returns a new value. Both ends of the range are inclusive days, the end is strictly after the
 * start, and a CLOSED year is never edited nor reopened. What needs other rows (the name being unique,
 * the ranges not overlapping, at most one ACTIVE year) is checked by the use case and the database.
 */
public record AcademicYear(UUID id, String name, LocalDate startDate, LocalDate endDate,
        AcademicYearStatus status, boolean active, Instant createdAt, Instant updatedAt) {

    public AcademicYear {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = Names.required(name, Names.MAX_NAME_LENGTH);
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            throw new InvalidAcademicStructureException();
        }
    }

    /** A new, active year. It cannot be born CLOSED. */
    public static AcademicYear create(UUID id, String name, LocalDate startDate, LocalDate endDate,
            AcademicYearStatus status, Instant now) {
        if (status == AcademicYearStatus.CLOSED) {
            throw new AcademicYearClosedException();
        }
        return new AcademicYear(id, name, startDate, endDate, status, true, now, now);
    }

    /** The year after an edit; a closed year refuses every edit, including the one that deactivates it. */
    public AcademicYear edit(String newName, LocalDate newStart, LocalDate newEnd,
            AcademicYearStatus newStatus, boolean newActive, Instant now) {
        if (isClosed()) {
            throw new AcademicYearClosedException();
        }
        return new AcademicYear(id, newName, newStart, newEnd, newStatus, newActive, createdAt, now);
    }

    public boolean isClosed() {
        return status == AcademicYearStatus.CLOSED;
    }

    /** Whether the inclusive range shares at least one day with this year. */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !otherStart.isAfter(endDate) && !otherEnd.isBefore(startDate);
    }
}
