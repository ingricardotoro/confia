package com.confia.guardians.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A current link of a student: the guardian (code, names, status; never a document nor an RTN) and the link. */
public record StudentGuardianResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID linkId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID guardianId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String guardianCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String guardianFirstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String guardianLastName,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "INACTIVE"}) String guardianStatus,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"FATHER", "MOTHER", "LEGAL_GUARDIAN", "SELF", "OTHER"}) String relationship,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0.01", maximum = "100",
                description = "Percentage of the financial responsibility, two decimals") BigDecimal financialResponsibilityPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isPrimaryPayer") boolean isPrimaryPayer,
        @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("isBillingRecipient") boolean isBillingRecipient,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The day the link started to apply") LocalDate validFrom) {

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "StudentGuardianResponse[linkId=" + linkId + "]";
    }
}
