package com.confia.organization.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The body of {@code POST /api/v1/organization/modalities}. */
public record CreateModalityRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30, description = "Code, unique in the institution")
        @NotBlank @Size(max = 30) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120) @NotBlank @Size(max = 120) String name) {
}
