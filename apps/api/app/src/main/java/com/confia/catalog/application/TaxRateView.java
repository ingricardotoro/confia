package com.confia.catalog.application;

import com.confia.catalog.domain.TaxRate;
import java.time.Instant;
import java.util.UUID;

/** A tax rate as the web layer may see it. */
public record TaxRateView(UUID id, String code, String name, boolean active, long version, Instant createdAt,
        Instant updatedAt) {

    static TaxRateView of(TaxRate rate) {
        return new TaxRateView(rate.id(), rate.code(), rate.name(), rate.active(), rate.version(), rate.createdAt(),
                rate.updatedAt());
    }
}
