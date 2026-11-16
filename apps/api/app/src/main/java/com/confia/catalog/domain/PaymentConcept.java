package com.confia.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What an institution charges for (for example "Colegiatura"). The code identifies it in the institution and never
 * changes once created, because other cuts reference it; everything else is edited with {@link #edit}. A concept is
 * deactivated, never deleted.
 */
public record PaymentConcept(UUID id, String code, String name, PaymentCategory category, boolean taxable,
        boolean recurring, boolean active, Instant createdAt, Instant updatedAt) {

    public PaymentConcept {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (category == null) {
            throw new InvalidPaymentConceptException();
        }
        code = Names.required(code, Names.MAX_CODE_LENGTH);
        name = Names.required(name, Names.MAX_NAME_LENGTH);
    }

    public static PaymentConcept create(UUID id, String code, String name, PaymentCategory category,
            boolean taxable, boolean recurring, Instant now) {
        return new PaymentConcept(id, code, name, category, taxable, recurring, true, now, now);
    }

    /** The same concept with its editable members replaced; the code, the identifier and the creation stay. */
    public PaymentConcept edit(String newName, PaymentCategory newCategory, boolean newTaxable,
            boolean newRecurring, boolean newActive, Instant now) {
        return new PaymentConcept(id, code, newName, newCategory, newTaxable, newRecurring, newActive, createdAt,
                now);
    }
}
