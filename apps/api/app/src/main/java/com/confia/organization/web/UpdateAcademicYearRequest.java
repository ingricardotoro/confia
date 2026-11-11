package com.confia.organization.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The body of {@code PUT /api/v1/organization/academic-years/{id}}: every editable member, replaced as
 * a whole. A CLOSED year is not edited whatever the body says.
 */
public record UpdateAcademicYearRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique in the institution")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "First day of the year, inclusive")
        @NotNull LocalDate startDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Last day of the year, inclusive; after the start")
        @NotNull LocalDate endDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"PLANNED", "ACTIVE", "CLOSED"},
                description = "Status; at most one year is ACTIVE and a CLOSED year never reopens")
        @NotNull @Pattern(regexp = "PLANNED|ACTIVE|CLOSED") String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "False deactivates the year")
        @JsonProperty("isActive") @NotNull Boolean isActive) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue
    public boolean isEndAfterStart() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
