package com.confia.catalog.application.directory;

import java.util.Objects;
import java.util.UUID;

/**
 * A payment concept as the other modules may see it: its identifier and whether it is active. A deactivated concept is
 * still found, so the caller can tell an inactive concept from one that does not exist.
 */
public record PaymentConceptRef(UUID id, boolean active) {

    public PaymentConceptRef {
        Objects.requireNonNull(id, "id");
    }
}
