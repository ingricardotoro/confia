package com.confia.organization.application;

import com.confia.organization.domain.Section;
import java.time.Instant;
import java.util.UUID;

/** A section as the web layer may see it. */
public record SectionView(UUID id, UUID academicYearId, UUID gradeId, String name, Integer capacity,
        boolean active, Instant createdAt, Instant updatedAt) {

    static SectionView of(Section section) {
        return new SectionView(section.id(), section.academicYearId(), section.gradeId(),
                section.name(), section.capacity(), section.active(), section.createdAt(),
                section.updatedAt());
    }
}
