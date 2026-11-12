package com.confia.guardians.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A student a guardian is linked to now, with the link: the code and the names of the student (never more). */
public record LinkedStudentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID linkId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentFirstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentLastName,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"FATHER", "MOTHER", "LEGAL_GUARDIAN", "SELF", "OTHER"}) String relationship,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0.01", maximum = "100",
                description = "Percentage of the financial responsibility, two decimals") BigDecimal financialResponsibilityPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isPrimaryPayer") boolean isPrimaryPayer,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isBillingRecipient") boolean isBillingRecipient,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The day the link started to apply") LocalDate validFrom) {

    /** The names of a minor never reach a log through this method. */
    @Override
    public String toString() {
        return "LinkedStudentResponse[linkId=" + linkId + "]";
    }
}
