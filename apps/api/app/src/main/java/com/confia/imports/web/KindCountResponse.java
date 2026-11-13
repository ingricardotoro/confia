package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** What the import of one kind created, and what its last application left rejected. */
public record KindCountResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rows created by every application of the kind, summed")
        int created,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Rows the newest application of the kind left rejected (not summed over applications)")
        int rejected) {
}
