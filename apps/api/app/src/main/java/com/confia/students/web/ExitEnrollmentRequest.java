package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The body of {@code POST /api/v1/enrollments/{id}/exit}: the end of an active enrollment as a withdrawal, a
 * transfer to another institution or a graduation.
 */
public record ExitEnrollmentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"WITHDRAWN", "TRANSFERRED", "GRADUATED"})
        @NotNull @Pattern(regexp = "WITHDRAWN|TRANSFERRED|GRADUATED") String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Not before the enrollment date and not in the future")
        @NotNull LocalDate withdrawnOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 500,
                description = "Required for WITHDRAWN and TRANSFERRED; optional for GRADUATED; 3 to 500 characters once stripped")
        @Size(max = 1000) String withdrawalReason) {

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "ExitEnrollmentRequest[REDACTED]";
    }
}
