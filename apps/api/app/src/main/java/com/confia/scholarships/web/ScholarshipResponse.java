package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A scholarship with its benefit, cap, covered concepts, state and version. Every amount is rounded by the server to the minor unit.
 * {@code benefitLocked} tells whether an award of it was approved, so the type and the value of its benefit no longer change.
 */
public record ScholarshipResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"PERCENTAGE", "FIXED_AMOUNT"}) String benefitType,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Percentage points with two decimals; only PERCENTAGE")
        String percent,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Only FIXED_AMOUNT") ScholarshipMoney fixedAmount,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Absent when there is no cap")
        ScholarshipMoney periodCap,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ConceptResponse> concepts,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean active,
        @Schema(requiredMode = RequiredMode.REQUIRED) long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean benefitLocked,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
