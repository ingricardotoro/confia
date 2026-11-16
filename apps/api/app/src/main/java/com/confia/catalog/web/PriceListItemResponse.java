package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One validity of the price of a concept in a list, as the API answers it. */
public record PriceListItemResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID priceListId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rounded by the server to the minor unit of the currency")
        MoneyDto price,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, format = "date",
                description = "Last day of the validity, inclusive; null while it is open")
        LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
