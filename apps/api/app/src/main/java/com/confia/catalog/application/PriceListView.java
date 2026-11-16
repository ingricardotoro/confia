package com.confia.catalog.application;

import com.confia.catalog.domain.PriceList;
import java.time.Instant;
import java.util.UUID;

/** A price list as the web layer may see it; the scope and the status are the names of the members of their sets. */
public record PriceListView(UUID id, UUID academicYearId, String name, String scope, UUID scopeRefId,
        String status, long version, Instant createdAt, Instant updatedAt) {

    static PriceListView of(PriceList list) {
        return new PriceListView(list.id(), list.academicYearId(), list.name(), list.scope().name(),
                list.scopeRefId(), list.status().name(), list.version(), list.createdAt(), list.updatedAt());
    }
}
