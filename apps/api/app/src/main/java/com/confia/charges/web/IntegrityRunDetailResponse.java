package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** An integrity run with the discrepancies it confirmed (none when its status is OK). */
public record IntegrityRunDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) IntegrityRunResponse run,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<IntegrityFindingResponse> findings) {
}
