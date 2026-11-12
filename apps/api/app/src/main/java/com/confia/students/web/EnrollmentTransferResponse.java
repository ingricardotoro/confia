package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One entry of the history of the sections of an enrollment: written once, never edited or deleted. */
public record EnrollmentTransferResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID fromSectionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String fromSectionName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID toSectionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String toSectionName,
        @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate transferredOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String reason,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The staff account that moved the enrollment")
        UUID transferredBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "EnrollmentTransferResponse[id=" + id + "]";
    }
}
