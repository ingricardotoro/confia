package com.confia.guardians.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One link of the set that replaces the guardians of a student. The percentage is read as an exact decimal (never as
 * a binary floating-point number): from 0.01 to 100 with at most two decimals. An unknown member is refused by the
 * JSON mapper.
 */
public record GuardianLinkRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The guardian; must be ACTIVE")
        @NotNull UUID guardianId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"FATHER", "MOTHER", "LEGAL_GUARDIAN", "SELF", "OTHER"})
        @NotNull @Pattern(regexp = "FATHER|MOTHER|LEGAL_GUARDIAN|SELF|OTHER") String relationship,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0.01", maximum = "100",
                description = "Percentage of the financial responsibility, 0.01 to 100 with at most two decimals; "
                        + "the percentages of the whole set add up to exactly 100")
        @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
        BigDecimal financialResponsibilityPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "At most one link of the set is the primary payer")
        @NotNull @JsonProperty("isPrimaryPayer") Boolean isPrimaryPayer,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "At most one link of the set is the billing recipient")
        @NotNull @JsonProperty("isBillingRecipient") Boolean isBillingRecipient) {
}
