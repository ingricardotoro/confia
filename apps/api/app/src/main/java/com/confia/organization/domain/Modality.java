package com.confia.organization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A modality of an institution (for example "Bachillerato"), identified by a code that is unique in it. */
public record Modality(UUID id, String code, String name, boolean active, Instant createdAt,
        Instant updatedAt) {

    public Modality {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        code = Names.required(code, Names.MAX_CODE_LENGTH);
        name = Names.required(name, Names.MAX_NAME_LENGTH);
    }

    public static Modality create(UUID id, String code, String name, Instant now) {
        return new Modality(id, code, name, true, now, now);
    }

    public Modality edit(String newCode, String newName, boolean newActive, Instant now) {
        return new Modality(id, newCode, newName, newActive, createdAt, now);
    }
}
