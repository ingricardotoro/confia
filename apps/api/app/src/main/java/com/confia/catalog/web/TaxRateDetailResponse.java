package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A tax rate with every period it has had, oldest first. */
public record TaxRateDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isActive") boolean isActive,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Send it back in the next PUT") long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<TaxRatePeriodResponse> periods) {
}
