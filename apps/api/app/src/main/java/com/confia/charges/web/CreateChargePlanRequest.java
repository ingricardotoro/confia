package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/charges/plans}. The institution is the one of the token and is never a member of the
 * body. The year, the target scope and the target reference cannot be changed after creation. The plan is born as a
 * draft.
 */
public record CreateChargePlanRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The academic year of the institution")
        @NotNull UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"MODALITY", "GRADE", "SECTION"})
        @NotBlank @Pattern(regexp = "MODALITY|GRADE|SECTION") String targetScope,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The modality, the grade or the section of the institution, as the target scope says")
        @NotNull UUID targetRefId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"MONTHLY", "BIMONTHLY", "ANNUAL", "ONE_TIME"})
        @NotBlank @Pattern(regexp = "MONTHLY|BIMONTHLY|ANNUAL|ONE_TIME") String frequency,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "28",
                description = "The day of the month charges fall due, from 1 to 28 so it exists in every month")
        @NotNull @Min(1) @Max(28) Integer dueDayOfMonth) {
}
