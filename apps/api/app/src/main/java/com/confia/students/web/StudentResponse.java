package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A student, as the API answers it. The national identity document is never a member: only whether there
 * is one and its mask, the last four characters.
 */
public record StudentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate birthDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"F", "M", "UNSPECIFIED"}) String sex,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "INACTIVE"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the student has a national identity document")
        boolean hasNationalId,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                description = "Four asterisks and the last four characters of the document; null without one")
        String nationalIdMasked,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
