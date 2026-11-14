package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of the reconciliations of the institution, newest first. */
public record ReconciliationPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ReconciliationResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
}
