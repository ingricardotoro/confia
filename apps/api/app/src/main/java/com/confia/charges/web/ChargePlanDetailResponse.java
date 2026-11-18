package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A charge plan with its items, in the order they were added. */
public record ChargePlanDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"MODALITY", "GRADE", "SECTION"})
        String targetScope,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The modality, the grade or the section of the plan")
        UUID targetRefId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"MONTHLY", "BIMONTHLY", "ANNUAL", "ONE_TIME"})
        String frequency,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "From 1 to 28") int dueDayOfMonth,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Send it back in the next PUT") long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ChargePlanItemResponse> items) {
}
