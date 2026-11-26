package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

/**
 * The body of {@code PUT /api/v1/scholarships/{id}}: the version the editor saw and the new name, benefit, cap and covered concepts.
 * The code and the active flag do not change. Once an award of the scholarship was approved, the type and the value of the benefit
 * cannot change.
 */
public record UpdateScholarshipRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "The version of the scholarship the editor saw")
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
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The cap of the benefit per period, greater than zero, in HNL; absent for no cap")
        @Valid ScholarshipMoney periodCap,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The payment concepts it covers: one or more")
        @NotNull @NotEmpty @Size(max = 50) Set<@NotNull UUID> conceptIds) {
}
