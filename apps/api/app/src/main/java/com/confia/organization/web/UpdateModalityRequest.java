package com.confia.organization.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The body of {@code PUT /api/v1/organization/modalities/{id}}: every editable member, replaced as a whole. */
public record UpdateModalityRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30, description = "Code, unique in the institution")
        @NotBlank @Size(max = 30) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the modality")
        @JsonProperty("isActive") @NotNull Boolean isActive) {
}
