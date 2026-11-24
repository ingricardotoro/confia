package com.confia.scholarships.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An award of a scholarship to a student: the scholarship and the student (by code, never by name), the validity, the status and the
 * decisions on it. The rows of a list carry no decisions. The reason of a rejection or a revocation never reaches a log through
 * {@code toString}.
 */
public record AwardResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID scholarshipId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String scholarshipCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String scholarshipName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "approved", "rejected", "revoked"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID requestedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant requestedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "The approval or the rejection, once there is one")
        @JsonInclude(JsonInclude.Include.NON_NULL) AwardDecisionResponse decision,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "The revocation, once there is one")
        @JsonInclude(JsonInclude.Include.NON_NULL) AwardDecisionResponse revocation) {

    @Override
    public String toString() {
        return "AwardResponse[id=" + id + ", scholarshipId=" + scholarshipId + ", studentId=" + studentId + ", status=" + status + "]";
    }
}
