package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The body of {@code PUT /api/v1/scholarships/discounts/{id}}: the version the editor saw and the new name, benefit, validity,
 * stackable flag and covered concepts. The code, the kind and the active flag do not change. Once an assignment of the discount was
 * approved, the type and the value of the benefit cannot change, the validity only shortens and the concepts are only removed.
 */
public record UpdateDiscountRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "The version of the discount the editor saw")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 120)
        @NotNull @Size(min = 1, max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"PERCENTAGE", "FIXED_AMOUNT"})
        @NotNull @Pattern(regexp = "^(PERCENTAGE|FIXED_AMOUNT)$") String benefitType,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, pattern = "^\\d{1,3}(\\.\\d{1,2})?$",
                description = "Percentage points greater than 0 and up to 100, with up to two decimals, as a string; only for PERCENTAGE")
        @Pattern(regexp = "^\\d{1,3}(\\.\\d{1,2})?$") String percent,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The fixed amount per charge, greater than zero, in HNL; only for FIXED_AMOUNT")
        @Valid ScholarshipMoney fixedAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date", description = "First day of the validity, both ends included")
        @NotNull LocalDate validFrom,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "Last day of the validity, inclusive, not before validFrom")
        @NotNull LocalDate validTo,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether it stacks with the other benefits of a charge")
        @NotNull Boolean stackable,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The payment concepts it covers: one or more")
        @NotNull @NotEmpty @Size(max = 50) Set<@NotNull UUID> conceptIds) {
}
