package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One validity of the percentage of a tax rate, as the API answers it. */
public record TaxRatePeriodResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID taxRateId,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^\\d{1,3}\\.\\d{4}$",
                description = "The percentage as a string with exactly four decimals; never rounded")
        String ratePercent,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, format = "date",
                description = "Last day of the validity, inclusive; null while it is open")
        LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
