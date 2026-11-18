package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/charges/plans/{id}/items}: a payment concept, how many periods are accrued and the
 * first day of the first one. The plan is the one of the path and the institution is the one of the token. It carries no
 * price: the accrual engine resolves it by date in the price lists.
 */
public record AddChargePlanItemRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "An active payment concept of the institution")
        @NotNull UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "36")
        @NotNull @Min(1) @Max(36) Integer periodsCount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "First day of the first period: the first day of a month, from 2000-01-01 to 2100-12-01")
        @NotNull @PeriodStartDay LocalDate firstPeriodOn) {
}
