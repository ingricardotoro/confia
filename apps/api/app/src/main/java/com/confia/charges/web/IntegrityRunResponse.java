package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The result of one nightly integrity check of the institution: when it ran, how much it checked, what it confirmed. */
public record IntegrityRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant startedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant finishedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"OK", "DISCREPANCIES"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) int chargesChecked,
        @Schema(requiredMode = RequiredMode.REQUIRED) int postingsChecked,
        @Schema(requiredMode = RequiredMode.REQUIRED) int discrepancyCount) {
}
