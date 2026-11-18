package com.confia.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The stable identity of a tax (for example the sales tax): a code that never changes and a name. It holds no
 * percentage: the percentages are its {@link TaxRatePeriod}s, each with its own validity, so a change of percentage is a
 * new period and never an edit (docs/04-cumplimiento-fiscal-sar.md, section 7). A rate is deactivated, never deleted; an
 * inactive rate takes no new period and cannot be assigned to a payment concept. Editing asks for the version the editor
 * saw.
 */
public record TaxRate(UUID id, String code, String name, boolean active, long version, Instant createdAt,
        Instant updatedAt) {

    public TaxRate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new InvalidCatalogValueException();
        }
        code = Names.code(code);
        name = Names.name(name);
    }

    public static TaxRate create(UUID id, String code, String name, Instant now) {
        return new TaxRate(id, code, name, true, 0, now, now);
    }

    /**
     * The same rate with a new name and active flag and the version advanced; the code stays.
     *
     * @param expectedVersion the version the editor saw
     * @throws StaleVersionException when it is not the current one
     */
    public TaxRate edit(long expectedVersion, String newName, boolean newActive, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        return new TaxRate(id, code, newName, newActive, version + 1, createdAt, now);
    }

    /** @throws InactiveReferenceException when the rate is inactive: it takes no new period */
    public void requireActive() {
        if (!active) {
            throw new InactiveReferenceException();
        }
    }
}
