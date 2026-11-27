package com.confia.scholarships.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The assignment of an agreement discount to a student: the discount and the student (by code, never by name), the validity, the
 * status and the decisions on it. The rows of a list carry no decisions. The reason of a rejection or a revocation never reaches a
 * log through {@code toString}.
 */
public record AssignmentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID discountId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String discountCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String discountName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "approved", "rejected", "revoked"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID requestedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant requestedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "The approval or the rejection, once there is one")
        @JsonInclude(JsonInclude.Include.NON_NULL) AssignmentDecisionResponse decision,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "The revocation, once there is one")
        @JsonInclude(JsonInclude.Include.NON_NULL) AssignmentDecisionResponse revocation,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The version of the discount when the assignment was requested: the approval is bound to it, and refused with discount-terms-changed when the discount changed since; absent for an assignment from before it was kept")
        Long discountVersion,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, allowableValues = {"PERCENTAGE", "FIXED_AMOUNT"},
                description = "The type of the benefit of the discount at the request")
        String benefitType,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Percentage points with two decimals; only PERCENTAGE")
        String percent,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, description = "Only FIXED_AMOUNT") ScholarshipMoney fixedAmount) {

    @Override
    public String toString() {
        return "AssignmentResponse[id=" + id + ", discountId=" + discountId + ", studentId=" + studentId + ", status=" + status + "]";
    }
}
