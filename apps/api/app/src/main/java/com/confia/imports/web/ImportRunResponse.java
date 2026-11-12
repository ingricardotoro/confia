package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * The answer of a simulation or an application: the batch and the rejections of that run, defects first and then the
 * rows skipped as already imported, each in line order, at most 200. The rejections are not stored: they are only here.
 */
public record ImportRunResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) ImportBatchResponse batch,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "At most 200") List<RejectionResponse> rejections,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "True when there were more rejections than the 200 returned") boolean rejectionsTruncated) {
}
