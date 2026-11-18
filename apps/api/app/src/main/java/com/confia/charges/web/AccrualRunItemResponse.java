package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** What a run did for one student and concept: a charge created, a charge found, or the error that kept it out. */
public record AccrualRunItemResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"CREATED", "ALREADY_ACCRUED", "ALREADY_ACCRUED_OTHER_PLAN", "ERROR"}) String outcome,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "Present when the outcome is ERROR: NO_PRICE, AMBIGUOUS_PRICE, TAX_RATE_MISSING, "
                        + "TAX_RATE_INACTIVE, TAX_RATE_NOT_IN_FORCE, CONCEPT_INACTIVE, STUDENT_INACTIVE, "
                        + "ACCOUNT_UNDER_REVIEW, OVERLAPPING_CHARGE or POSTING_FAILED") String errorCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID chargeId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID existingChargeId) {
}
