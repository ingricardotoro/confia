package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/catalog/payment-concepts/{id}}: every editable member, replaced as a whole. It has no
 * code: the code is immutable, and a body that carries one is refused as an unknown member.
 */
public record UpdatePaymentConceptRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"})
        @NotBlank @Pattern(regexp = "TUITION|ENROLLMENT|TRANSPORT|MATERIAL|FINE|OTHER") String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isTaxable") @NotNull Boolean isTaxable,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isRecurring") @NotNull Boolean isRecurring,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the concept")
        @JsonProperty("isActive") @NotNull Boolean isActive) {
}
