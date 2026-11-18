package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of integrity runs, most recent first. */
public record IntegrityRunPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<IntegrityRunResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
}
