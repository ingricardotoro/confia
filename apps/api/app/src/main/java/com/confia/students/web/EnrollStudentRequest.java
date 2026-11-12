package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/enrollments}. The academic year is not a member: it is the year of the
 * section. An unknown member, such as an institution, is refused by the JSON mapper.
 */
public record EnrollStudentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The student; must be ACTIVE")
        @NotNull UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The section; must be active, with a free seat")
        @NotNull UUID sectionId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Inside the academic year of the section (both ends included) and not in the future")
        @NotNull LocalDate enrolledOn) {
}
