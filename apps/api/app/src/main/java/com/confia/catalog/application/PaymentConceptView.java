package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.TaxRate;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment concept as the web layer may see it; the category is the name of the member of the closed set. The tax rate
 * is shown by its identifier, its code and its name, all {@code null} while the concept has none.
 */
public record PaymentConceptView(UUID id, String code, String name, String category, boolean taxable,
        boolean recurring, boolean active, UUID taxRateId, String taxRateCode, String taxRateName, long version,
        Instant createdAt, Instant updatedAt) {

    /** @param taxRate the tax rate of the concept, or {@code null} when it has none */
    static PaymentConceptView of(PaymentConcept concept, TaxRate taxRate) {
        return new PaymentConceptView(concept.id(), concept.code(), concept.name(), concept.category().name(),
                concept.taxable(), concept.recurring(), concept.active(), concept.taxRateId(),
                taxRate == null ? null : taxRate.code(), taxRate == null ? null : taxRate.name(), concept.version(),
                concept.createdAt(), concept.updatedAt());
    }
}
