package com.confia.charges.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * The terms of a payment concept that the engine needs: whether it is active, its category, whether it is taxable and the tax
 * rate assigned to it (none while {@code taxRateId} is {@code null}).
 */
public record ConceptTerms(UUID conceptId, boolean active, String category, boolean taxable, UUID taxRateId) {

    public ConceptTerms {
        Objects.requireNonNull(conceptId, "conceptId");
        Objects.requireNonNull(category, "category");
    }
}
