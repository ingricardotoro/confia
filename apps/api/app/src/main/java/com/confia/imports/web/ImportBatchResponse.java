package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A batch of the import: counts and the SHA-256 of the file, never a value of it. Every data row of the file is exactly
 * one of valid, rejected or skipped, so {@code totalRows = validRows + rejectedRows + skippedRows}. A simulation
 * ({@code VALIDATED}) has {@code createdRows = 0} and no {@code appliedAt}; an application ({@code APPLIED}) has {@code
 * createdRows = validRows}.
 */
public record ImportBatchResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"STUDENTS", "GUARDIANS", "GUARDIAN_LINKS", "ENROLLMENTS"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"VALIDATED", "APPLIED"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$",
                description = "SHA-256 of the file, in lowercase hexadecimal") String fileSha256,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Data rows of the file") int totalRows,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Rows without a defect that are not imported yet (simulation) or were created") int validRows,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rows with at least one defect") int rejectedRows,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Students registered by this batch") int createdRows,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rows skipped because they were imported before")
        int skippedRows,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Only for APPLIED")
        Instant appliedAt) {
}
