package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** The body of {@code POST /api/v1/enrollments/{id}/transfer}: a move to another section of the same year. */
public record TransferEnrollmentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "A section of the same academic year, different from the current one, active and with a free seat")
        @NotNull UUID toSectionId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Not before the enrollment date, not in the future and not after the end of the year")
        @NotNull LocalDate transferredOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 500,
                description = "Why; optional, 3 to 500 characters once stripped")
        @Size(max = 1000) String reason) {

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "TransferEnrollmentRequest[REDACTED]";
    }
}
