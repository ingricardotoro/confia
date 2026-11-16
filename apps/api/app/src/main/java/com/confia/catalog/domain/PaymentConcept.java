package com.confia.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What an institution charges for (for example "Colegiatura"). The code identifies it in the institution and never
 * changes once created, because other cuts reference it; everything else is edited with {@link #edit}. A concept is
 * deactivated, never deleted. The {@code version} starts at zero and grows with every edit: an edit that names a version
 * other than the current one is refused ({@link StaleVersionException}), so two people editing at once never overwrite
 * each other without knowing.
 */
public record PaymentConcept(UUID id, String code, String name, PaymentCategory category, boolean taxable,
        boolean recurring, boolean active, long version, Instant createdAt, Instant updatedAt) {

    public PaymentConcept {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (category == null || version < 0) {
            throw new InvalidCatalogValueException();
        }
        code = Names.code(code);
        name = Names.name(name);
    }

    public static PaymentConcept create(UUID id, String code, String name, PaymentCategory category,
            boolean taxable, boolean recurring, Instant now) {
        return new PaymentConcept(id, code, name, category, taxable, recurring, true, 0, now, now);
    }

    /**
     * The same concept with its editable members replaced and the version advanced; the code, the identifier and the
     * creation stay.
     *
     * @param expectedVersion the version the editor saw
     * @throws StaleVersionException when it is not the current one
     */
    public PaymentConcept edit(long expectedVersion, String newName, PaymentCategory newCategory,
            boolean newTaxable, boolean newRecurring, boolean newActive, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        return new PaymentConcept(id, code, newName, newCategory, newTaxable, newRecurring, newActive, version + 1,
                createdAt, now);
    }
}
