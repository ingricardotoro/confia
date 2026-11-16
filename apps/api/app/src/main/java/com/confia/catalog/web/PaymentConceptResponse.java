package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** A payment concept, as the API answers it. */
public record PaymentConceptResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"}) String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isTaxable") boolean isTaxable,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isRecurring") boolean isRecurring,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isActive") boolean isActive,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
