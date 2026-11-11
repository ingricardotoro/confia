package com.confia.organization.application;

import com.confia.organization.domain.AcademicYear;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An academic year as the web layer may see it: plain values, the status as its name. */
public record AcademicYearView(UUID id, String name, LocalDate startDate, LocalDate endDate,
        String status, boolean active, Instant createdAt, Instant updatedAt) {

    static AcademicYearView of(AcademicYear year) {
        return new AcademicYearView(year.id(), year.name(), year.startDate(), year.endDate(),
                year.status().name(), year.active(), year.createdAt(), year.updatedAt());
    }
}
