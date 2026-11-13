package com.confia.imports.web;

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
 * The body of {@code POST /api/v1/imports/reconciliations}. The counts of the migration are not here: the server computes
 * them. An unknown member is refused by the JSON mapper.
 */
public record SignReconciliationRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The academic year the reconciliation is about")
        @NotNull UUID academicYearId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100,
                description = "Reference of the official roster (for example an office document number); never a name or a document of a person")
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[^\\p{Cc}]*$") String officialReference,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", maximum = "1000000",
                description = "Total of enrollments the official roster declares")
        @NotNull @Min(0) @Max(1_000_000) Integer officialEnrollmentCount) {
}
