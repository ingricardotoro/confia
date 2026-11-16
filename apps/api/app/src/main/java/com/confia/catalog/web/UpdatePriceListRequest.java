package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/catalog/price-lists/{id}}: the name and the status, replaced as a whole, and the version
 * the editor read. It has no year, scope nor reference: they are immutable, and a body that carries one is refused as an
 * unknown member.
 */
public record UpdatePriceListRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0",
                description = "The version of the list the editor read; a different one answers 409 stale-version")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "ARCHIVED"},
                description = "ARCHIVED archives the list: it takes no new price")
        @NotBlank @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {
}
