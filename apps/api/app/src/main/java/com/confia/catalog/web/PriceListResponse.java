package com.confia.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** A price list, as the API answers it. */
public record PriceListResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTION", "MODALITY", "GRADE"}) String scope,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The modality or the grade of the list; null when the scope is INSTITUTION")
        UUID scopeRefId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "ARCHIVED"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Send it back in the next PUT") long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
