package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/scholarships/discounts}: the discount to create. The institution is the one of the token. The kind
 * is {@code campaign} (it applies by itself to every charge of a covered concept inside its validity) or {@code agreement} (it applies
 * to the students with an approved assignment). The benefit is a percentage ({@code PERCENTAGE}, with {@code percent}) or a fixed
 * amount ({@code FIXED_AMOUNT}, with {@code fixedAmount}), and the other member must be absent.
 */
public record CreateDiscountRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$",
                description = "Unique in the institution whatever its capitals; letters, digits, underscore and hyphen")
        @NotNull @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$") String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 120)
        @NotNull @Size(min = 1, max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"campaign", "agreement"})
        @NotNull @Pattern(regexp = "^(campaign|agreement)$") String kind,
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
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Whether it stacks with the other benefits of a charge; when any benefit is not stackable only the greatest applies")
        @NotNull Boolean stackable,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The payment concepts it covers: one or more")
        @NotNull @NotEmpty @Size(max = 50) Set<@NotNull UUID> conceptIds) {
}
