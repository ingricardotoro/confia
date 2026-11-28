package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/scholarships/discount-assignments}: the request of the assignment of an agreement discount to a
 * student, with a validity inside the one of the discount, both days included. The institution and the requester are the ones of the
 * token.
 */
public record RequestDiscountAssignmentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID discountId,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "First day of the validity, inside the one of the discount")
        @NotNull LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "Last day of the validity, inclusive, inside the one of the discount and not before validFrom")
        @NotNull LocalDate validTo) {

    @Override
    public String toString() {
        return "RequestDiscountAssignmentRequest[discountId=" + discountId + ", studentId=" + studentId + "]";
    }
}
