package com.confia.organization.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** The body of {@code POST /api/v1/organization/sections}; the year must not be CLOSED and the grade must be active. */
public record CreateSectionRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The academic year; fixed afterwards")
        @NotNull UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The grade; fixed afterwards")
        @NotNull UUID gradeId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique per year and grade")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, minimum = "1", description = "Capacity; above zero when present")
        @Positive Integer capacity) {
}
