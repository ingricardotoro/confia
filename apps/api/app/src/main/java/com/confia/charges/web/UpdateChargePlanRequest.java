package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/charges/plans/{id}}: the name, the frequency, the due day and the status, replaced as a
 * whole, and the version the editor read. It has no year, target scope nor reference: they are immutable, and a body that
 * carries one is refused as an unknown member.
 */
public record UpdateChargePlanRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0",
                description = "The version of the plan the editor read; a different one answers 409 stale-version")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"MONTHLY", "BIMONTHLY", "ANNUAL", "ONE_TIME"},
                description = "Only a draft changes its frequency; an active plan must send the one it has")
        @NotBlank @Pattern(regexp = "MONTHLY|BIMONTHLY|ANNUAL|ONE_TIME") String frequency,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "1", maximum = "28",
                description = "Only a draft changes its due day; an active plan must send the one it has")
        @NotNull @Min(1) @Max(28) Integer dueDayOfMonth,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"},
                description = "The same status, or the next one: DRAFT to ACTIVE (needs at least one item) and ACTIVE "
                        + "to ARCHIVED. An archived plan never changes")
        @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED") String status) {
}
