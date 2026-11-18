package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** The body of {@code POST /api/v1/charges/plans/{id}/accrual-preview}: the first day of the period to preview. */
public record AccrualPreviewRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "First day of one of the periods the plan lists")
        @NotNull @PeriodStartDay LocalDate periodStart) {
}
