package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/** What a reconciliation signed now for the year would record; nothing is written. */
public record ReconciliationPreviewResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Active enrollments of the year now") int enrollmentsActive,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse students,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse guardians,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse guardianLinks,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse enrollments) {
}
