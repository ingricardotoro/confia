package com.confia.students.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An enrollment, as the API answers it: with the code and the names of the student (never the document), and
 * the names of the academic year, the section, the grade and the modality, which are derived from the
 * section. The history of sections is present in the detail and in the answers of the writes, and absent in
 * a list.
 */
public record StudentEnrollmentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentFirstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentLastName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String academicYearName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID sectionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String sectionName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID gradeId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String gradeName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String modalityName,
        @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate enrolledOn,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"ACTIVE", "WITHDRAWN", "TRANSFERRED", "GRADUATED"}) String status,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The day it left ACTIVE; null while active") LocalDate withdrawnOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String withdrawalReason,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED,
                description = "The sections the enrollment has been in, oldest first; absent in a list")
        @JsonInclude(JsonInclude.Include.NON_NULL) List<EnrollmentTransferResponse> transfers) {

    /** Spring logs the object it writes at DEBUG: the names of a minor and the reason never reach a log through it. */
    @Override
    public String toString() {
        return "StudentEnrollmentResponse[id=" + id + "]";
    }
}
