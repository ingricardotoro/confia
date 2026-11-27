package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /api/v1/scholarships/discounts/{id}/approve}: the version of the discount the person who approves saw, so
 * what is approved is what was shown and nothing that changed afterwards.
 */
public record DiscountDecisionRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "The version of the discount the decider saw")
        @NotNull @Min(0) Long version) {
}
