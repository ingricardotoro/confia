package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An accrual run, as the API answers it. The key of its request never leaves the server. */
public record AccrualRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID planId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"QUEUED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "REJECTED", "FAILED"})
        String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the request carried a preview digest")
        boolean previewChecked,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant queuedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) Instant startedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) Instant finishedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) int studentsEvaluated,
        @Schema(requiredMode = RequiredMode.REQUIRED) int chargesCreated,
        @Schema(requiredMode = RequiredMode.REQUIRED) int chargesExisting,
        @Schema(requiredMode = RequiredMode.REQUIRED) int chargesFailed,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "Why a rejected or failed run did not run: plan-not-active, period-not-in-plan, "
                        + "posting-accounts-incomplete, period-in-future, plan-target-outside-year, preview-outdated "
                        + "or retries-exhausted") String failureCode) {
}
