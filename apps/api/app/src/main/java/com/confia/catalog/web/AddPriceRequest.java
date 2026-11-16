package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/catalog/price-lists/{id}/items}: the price of a concept from a day, optionally to
 * another. The list is the one of the path and the institution is the one of the token. When the concept already has an
 * open validity that this price replaces, the server closes it the day before {@code validFrom}.
 */
public record AddPriceRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "An active payment concept of the institution")
        @NotNull UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Greater than zero, up to two decimals")
        @NotNull @Valid PriceInputDto price,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "First day of the validity, from 2000-01-01 to 2100-12-31")
        @NotNull @ValidityDay LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, format = "date",
                description = "Last day of the validity, inclusive; absent for no end. Not before validFrom")
        @ValidityDay LocalDate validTo) {
}
