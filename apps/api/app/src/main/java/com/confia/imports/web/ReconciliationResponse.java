package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A signed reconciliation of the migration: counts, the reference of the official roster and who signed when; nothing
 * of a person. It is never changed.
 */
public record ReconciliationResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Reference of the official roster") String officialReference,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Total of enrollments the official roster declares")
        int officialEnrollmentCount,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Active enrollments of the year when it was signed")
        int enrollmentsActive,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Active enrollments minus the official total: positive when the system holds more than the roster says")
        int difference,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse students,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse guardians,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse guardianLinks,
        @Schema(requiredMode = RequiredMode.REQUIRED) KindCountResponse enrollments,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The account that signed") UUID signedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant signedAt) {
}
