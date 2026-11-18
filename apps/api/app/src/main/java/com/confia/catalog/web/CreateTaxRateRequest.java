package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/catalog/tax-rates}: the identity of a tax. It carries no percentage: the percentages
 * are added as periods with their own validity. The institution is the one of the token and is never a member of the body.
 */
public record CreateTaxRateRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30, pattern = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$",
                description = "Letters, digits, underscore and hyphen, starting with a letter or digit; unique in the institution whatever its capitals; it cannot be changed later")
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$") String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name) {
}
