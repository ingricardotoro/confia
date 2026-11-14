package com.confia.organization.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The body of {@code PUT /api/v1/organization/grades/{id}}: every editable member, replaced as a whole. */
public record UpdateGradeRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique in the modality")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Order, unique in the modality")
        @NotNull @PositiveOrZero Integer ordinal,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the grade")
        @JsonProperty("isActive") @NotNull Boolean isActive) {
}
