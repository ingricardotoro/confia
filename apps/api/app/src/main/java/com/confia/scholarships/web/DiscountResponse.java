package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A discount with its kind, benefit, validity, stackable flag, covered concepts, state and version. Every amount is rounded by the
 * server to the minor unit. {@code inUse} tells whether an assignment of it was approved or a charge was issued with it, so the type and the value of its benefit no
 * longer change and its validity and concepts only narrow.
 */
public record DiscountResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"campaign", "agreement"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"PERCENTAGE", "FIXED_AMOUNT"}) String benefitType,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Percentage points with two decimals; only PERCENTAGE")
        String percent,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Only FIXED_AMOUNT") ScholarshipMoney fixedAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean stackable,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ConceptResponse> concepts,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean active,
        @Schema(requiredMode = RequiredMode.REQUIRED) long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean inUse,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
