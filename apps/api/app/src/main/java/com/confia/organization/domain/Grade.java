package com.confia.organization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A grade of a modality. Its name and its ordinal are unique within the modality (the database says
 * so), and the modality is fixed at creation, when it must be active.
 */
public record Grade(UUID id, UUID modalityId, String name, int ordinal, boolean active,
        Instant createdAt, Instant updatedAt) {

    public Grade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modalityId, "modalityId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = Names.required(name, Names.MAX_NAME_LENGTH);
        if (ordinal < 0) {
            throw new InvalidAcademicStructureException();
        }
    }

    /** A new grade; the modality must be active. */
    public static Grade create(UUID id, Modality modality, String name, int ordinal, Instant now) {
        if (!modality.active()) {
            throw new InactiveReferenceException();
        }
        return new Grade(id, modality.id(), name, ordinal, true, now, now);
    }

    /** Deactivating a grade, or its modality, leaves the sections already created untouched. */
    public Grade edit(String newName, int newOrdinal, boolean newActive, Instant now) {
        return new Grade(id, modalityId, newName, newOrdinal, newActive, createdAt, now);
    }
}
