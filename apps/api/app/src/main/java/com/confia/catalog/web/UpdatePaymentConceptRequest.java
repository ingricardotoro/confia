package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code PUT /api/v1/catalog/payment-concepts/{id}}: every editable member, replaced as a whole, and the
 * version the editor read (optimistic concurrency, docs/cortes/catalog-price-lists.md). It has no
 * code: the code is immutable, and a body that carries one is refused as an unknown member.
 */
public record UpdatePaymentConceptRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0",
                description = "The version of the concept the editor read; a different one answers 409 stale-version")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"})
        @NotBlank @Pattern(regexp = "TUITION|ENROLLMENT|TRANSPORT|MATERIAL|FINE|OTHER") String category,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isTaxable") @NotNull Boolean isTaxable,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isRecurring") @NotNull Boolean isRecurring,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the concept")
        @JsonProperty("isActive") @NotNull Boolean isActive,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "A tax rate of the institution, active unless the concept already has it and is not being reactivated; required when isTaxable is true (except for a legacy taxable concept that has none and stays so) and absent when it is false")
        UUID taxRateId) {
}
