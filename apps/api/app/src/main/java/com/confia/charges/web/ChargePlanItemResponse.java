package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An item of a charge plan, as the API answers it. It carries no price: the accrual engine resolves it by date. */
public record ChargePlanItemResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID planId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "From 1 to 36") int periodsCount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "The first day of the first period") LocalDate firstPeriodOn,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
