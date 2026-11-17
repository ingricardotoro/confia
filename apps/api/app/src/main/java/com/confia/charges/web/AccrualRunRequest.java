package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * The body of {@code POST /api/v1/charges/plans/{id}/accrual-runs}: the period and, optionally, the digest the preview
 * answered. With a digest, a calculation that changed since the preview makes the run end rejected with nothing written.
 */
public record AccrualRunRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "First day of one of the periods the plan lists")
        @NotNull @PeriodStartDay LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, pattern = "^[0-9a-f]{64}$",
                description = "The previewDigest of the preview the user confirmed")
        @Pattern(regexp = "^[0-9a-f]{64}$") String previewDigest) {
}
