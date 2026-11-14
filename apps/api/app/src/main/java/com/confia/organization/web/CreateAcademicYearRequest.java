package com.confia.organization.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The body of {@code POST /api/v1/organization/academic-years}. The status is optional: PLANNED when
 * absent, and a year is never created CLOSED. An unknown member is refused by the JSON mapper.
 */
public record CreateAcademicYearRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique in the institution")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "First day of the year, inclusive")
        @NotNull LocalDate startDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Last day of the year, inclusive; after the start")
        @NotNull LocalDate endDate,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, allowableValues = {"PLANNED", "ACTIVE"},
                description = "Initial status; PLANNED when absent")
        @Pattern(regexp = "PLANNED|ACTIVE") String status) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue
    public boolean isEndAfterStart() {
        return startDate == null || endDate == null || endDate.isAfter(startDate);
    }
}
