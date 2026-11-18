package com.confia.charges.domain;

import com.confia.kernel.Percentage;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A tax rate of the catalog as the engine reads it: whether it is active and its validities. The percentage of a validity
 * is in percentage points ({@code 15} is 15 %). Nothing here is a rate of the law; the values come from the catalog.
 */
public record TaxRateInfo(UUID taxRateId, boolean active, List<Validity> validities) {

    public TaxRateInfo {
        Objects.requireNonNull(taxRateId, "taxRateId");
        validities = List.copyOf(Objects.requireNonNull(validities, "validities"));
    }

    /** One validity of the rate: the percentage in force from the first to the last day (open while it is {@code null}). */
    public record Validity(UUID periodId, Percentage percent, LocalDate validFrom, LocalDate validTo) {

        public Validity {
            Objects.requireNonNull(periodId, "periodId");
            Objects.requireNonNull(percent, "percent");
            Objects.requireNonNull(validFrom, "validFrom");
        }

        /** Whether the validity covers the date, both ends included. */
        public boolean covers(LocalDate date) {
            return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
        }
    }
}
