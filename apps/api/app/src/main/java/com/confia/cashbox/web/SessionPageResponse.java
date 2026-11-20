package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of cash sessions. */
public record SessionPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<SessionSummaryResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = RequiredMode.REQUIRED) int page,
        @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
}
