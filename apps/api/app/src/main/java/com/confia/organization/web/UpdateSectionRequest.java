package com.confia.organization.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The body of {@code PUT /api/v1/organization/sections/{id}}: every editable member, replaced as a whole. */
public record UpdateSectionRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique per year and grade")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, minimum = "1", description = "Capacity; above zero when present, none when absent")
        @Positive Integer capacity,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the section")
        @JsonProperty("isActive") @NotNull Boolean isActive) {
}
