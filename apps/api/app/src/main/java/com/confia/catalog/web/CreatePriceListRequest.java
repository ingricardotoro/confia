package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/catalog/price-lists}. The institution is the one of the token and is never a member
 * of the body. The year, the scope and the reference cannot be changed after creation.
 */
public record CreatePriceListRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The academic year of the institution") @NotNull UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTION", "MODALITY", "GRADE"})
        @NotBlank @Pattern(regexp = "INSTITUTION|MODALITY|GRADE") String scope,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The modality or the grade of the institution; absent when the scope is INSTITUTION, "
                        + "required for the other two")
        UUID scopeRefId) {
}
