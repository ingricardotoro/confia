package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The body of {@code POST /api/v1/catalog/tax-rates/{id}/periods}: a percentage from a day, optionally to another. The
 * rate is the one of the path and the institution is the one of the token. When the rate already has an open period that
 * this one replaces, the server closes it the day before {@code validFrom}.
 */
public record AddTaxRatePeriodRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^\\d{1,3}(\\.\\d{1,4})?$",
                description = "Percentage greater than zero and at most 100, with up to four decimals, as a string (never a JSON number)")
        @NotNull @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,4})?$") @JsonDeserialize(using = StrictTextDeserializer.class)
        String ratePercent,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "First day of the validity, from 2000-01-01 to 2100-12-31")
        @NotNull @ValidityDay LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, format = "date",
                description = "Last day of the validity, inclusive; absent for no end. Not before validFrom")
        @ValidityDay LocalDate validTo) {
}
