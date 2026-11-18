package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A period of a plan that a run can accrue, with its most recent run. The server lists periods; the browser never computes one. */
public record AccrualPeriodResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Year and month of the first day, like 2027-02")
        String label,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate dueOn,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<UUID> paymentConceptIds,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualRunResponse lastRun) {
}
