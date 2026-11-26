package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** A payment concept that a scholarship covers. */
public record ConceptResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name) {
}
