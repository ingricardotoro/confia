package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** The body of {@code POST /api/v1/catalog/payment-concepts}. */
public record CreatePaymentConceptRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30, pattern = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$",
                description = "Letters, digits, underscore and hyphen, starting with a letter or digit; unique in the institution whatever its capitals; it cannot be changed later")
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$") String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"})
        @NotBlank @Pattern(regexp = "TUITION|ENROLLMENT|TRANSPORT|MATERIAL|FINE|OTHER") String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isTaxable") @NotNull Boolean isTaxable,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isRecurring") @NotNull Boolean isRecurring,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "An active tax rate of the institution; required when isTaxable is true and absent when it is false")
        UUID taxRateId) {
}
