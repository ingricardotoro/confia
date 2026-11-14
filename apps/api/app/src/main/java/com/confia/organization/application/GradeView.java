package com.confia.organization.application;

import com.confia.organization.domain.Grade;
import java.time.Instant;
import java.util.UUID;

/** A grade as the web layer may see it. */
public record GradeView(UUID id, UUID modalityId, String name, int ordinal, boolean active,
        Instant createdAt, Instant updatedAt) {

    static GradeView of(Grade grade) {
        return new GradeView(grade.id(), grade.modalityId(), grade.name(), grade.ordinal(),
                grade.active(), grade.createdAt(), grade.updatedAt());
    }
}
