package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The body of {@code POST /api/v1/catalog/payment-concepts}. */
public record CreatePaymentConceptRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30,
                description = "Code, unique in the institution whatever its capitals; it cannot be changed later")
        @NotBlank @Size(max = 30) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"})
        @NotBlank @Pattern(regexp = "TUITION|ENROLLMENT|TRANSPORT|MATERIAL|FINE|OTHER") String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isTaxable") @NotNull Boolean isTaxable,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isRecurring") @NotNull Boolean isRecurring) {
}
