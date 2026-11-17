package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** An accrual run with one page of its items. */
public record AccrualRunDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) AccrualRunResponse run,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<AccrualRunItemResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long totalItems,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
}
