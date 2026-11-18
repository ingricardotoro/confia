package com.confia.catalog.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/catalog/tax-rates/{id}}: the name and the active flag, replaced as a whole, and the
 * version the editor read. It has no code and no percentage: the code is immutable, the percentages are periods, and a
 * body that carries either is refused as an unknown member.
 */
public record UpdateTaxRateRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0",
                description = "The version of the tax rate the editor read; a different one answers 409 stale-version")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "False deactivates the tax rate: it takes no new period and cannot be assigned to a concept")
        @JsonProperty("isActive") @NotNull Boolean isActive) {
}
