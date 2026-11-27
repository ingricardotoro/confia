package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The terms proposed for an approved campaign discount, which wait for a person other than {@code requestedBy}: they are NOT the
 * terms in force. {@code reactivate} says the discount would also be turned on. Every amount is rounded by the server.
 */
public record DiscountChangeResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"PERCENTAGE", "FIXED_AMOUNT"}) String benefitType,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Percentage points with two decimals; only PERCENTAGE")
        String percent,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Only FIXED_AMOUNT") ScholarshipMoney fixedAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean stackable,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether approving it also turns the discount on") boolean reactivate,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ConceptResponse> concepts,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Who proposed it: never the one who approves it") UUID requestedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant requestedAt) {
}
