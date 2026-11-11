package com.confia.organization.application;

import com.confia.organization.domain.Modality;
import java.time.Instant;
import java.util.UUID;

/** A modality as the web layer may see it. */
public record ModalityView(UUID id, String code, String name, boolean active, Instant createdAt,
        Instant updatedAt) {

    static ModalityView of(Modality modality) {
        return new ModalityView(modality.id(), modality.code(), modality.name(), modality.active(),
                modality.createdAt(), modality.updatedAt());
    }
}
