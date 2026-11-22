package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The part of a confirmed payment that was applied to one charge, with the concept and the period that identify it. */
public record AllocationResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID chargeId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptName,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "The period as YYYY-MM")
        String periodLabel,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, format = "date") LocalDate dueOn,
        @Schema(requiredMode = RequiredMode.REQUIRED) PaymentMoney amount,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant allocatedAt) {
}
