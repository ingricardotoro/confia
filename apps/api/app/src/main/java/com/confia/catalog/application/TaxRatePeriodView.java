package com.confia.catalog.application;

import com.confia.catalog.domain.TaxRatePeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A period of a tax rate as the web layer may see it. The percentage is a text with exactly four decimals, as it is
 * stored: it is a percentage and never rounded.
 */
public record TaxRatePeriodView(UUID id, UUID taxRateId, String ratePercent, LocalDate validFrom, LocalDate validTo,
        Instant createdAt) {

    static TaxRatePeriodView of(TaxRatePeriod period) {
        return new TaxRatePeriodView(period.id(), period.taxRateId(), period.ratePercent().toPlainString(),
                period.validFrom(), period.validTo(), period.createdAt());
    }
}
