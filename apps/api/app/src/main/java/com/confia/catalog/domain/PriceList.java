package com.confia.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A list of prices for one academic year, for the whole institution, for one modality or for one grade. The year, the
 * scope and the reference of the scope never change once the list exists; the name and the status do, with {@link
 * #edit}, which asks for the version the editor saw. A list is archived, never deleted, and an archived list takes no
 * new price ({@link #requireAcceptsPrices()}). The prices themselves are {@link PriceListItem}s.
 *
 * @param scopeRefId the modality or the grade the list applies to; {@code null} exactly when the scope is the whole
 *     institution
 */
public record PriceList(UUID id, UUID academicYearId, String name, PriceListScope scope, UUID scopeRefId,
        PriceListStatus status, long version, Instant createdAt, Instant updatedAt) {

    public PriceList {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (academicYearId == null || scope == null || status == null || version < 0
                || (scope == PriceListScope.INSTITUTION) != (scopeRefId == null)) {
            throw new InvalidCatalogValueException();
        }
        name = Names.name(name);
    }

    public static PriceList create(UUID id, UUID academicYearId, String name, PriceListScope scope,
            UUID scopeRefId, Instant now) {
        return new PriceList(id, academicYearId, name, scope, scopeRefId, PriceListStatus.ACTIVE, 0, now, now);
    }

    /**
     * The same list with a new name and status and the version advanced.
     *
     * @param expectedVersion the version the editor saw
     * @throws StaleVersionException when it is not the current one
     */
    public PriceList edit(long expectedVersion, String newName, PriceListStatus newStatus, Instant now) {
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        return new PriceList(id, academicYearId, newName, scope, scopeRefId, newStatus, version + 1, createdAt, now);
    }

    /** @throws PriceListArchivedException when the list is archived */
    public void requireAcceptsPrices() {
        if (status == PriceListStatus.ARCHIVED) {
            throw new PriceListArchivedException();
        }
    }
}
