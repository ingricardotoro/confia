package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of the batches of the institution, newest first. */
public record ImportBatchPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ImportBatchResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
}
