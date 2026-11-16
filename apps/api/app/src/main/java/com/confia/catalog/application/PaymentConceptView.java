package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentConcept;
import java.time.Instant;
import java.util.UUID;

/** A payment concept as the web layer may see it; the category is the name of the member of the closed set. */
public record PaymentConceptView(UUID id, String code, String name, String category, boolean taxable,
        boolean recurring, boolean active, Instant createdAt, Instant updatedAt) {

    static PaymentConceptView of(PaymentConcept concept) {
        return new PaymentConceptView(concept.id(), concept.code(), concept.name(), concept.category().name(),
                concept.taxable(), concept.recurring(), concept.active(), concept.createdAt(),
                concept.updatedAt());
    }
}
