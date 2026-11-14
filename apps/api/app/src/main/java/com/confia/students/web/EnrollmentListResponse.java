package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** The enrollments of one student, the most recent first. Not paged: a student has a handful. */
public record EnrollmentListResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<StudentEnrollmentResponse> items) {
}
