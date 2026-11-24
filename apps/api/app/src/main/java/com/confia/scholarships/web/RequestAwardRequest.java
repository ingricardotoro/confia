package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/scholarship-awards}: the request of an award of a scholarship to a student for an academic year,
 * with a validity inside that year, both days included. The institution and the requester are the ones of the token.
 */
public record RequestAwardRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID scholarshipId,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "First day of the validity, inside the academic year")
        @NotNull LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "Last day of the validity, inclusive, inside the academic year and not before validFrom")
        @NotNull LocalDate validTo) {

    @Override
    public String toString() {
        return "RequestAwardRequest[scholarshipId=" + scholarshipId + ", studentId=" + studentId + "]";
    }
}
