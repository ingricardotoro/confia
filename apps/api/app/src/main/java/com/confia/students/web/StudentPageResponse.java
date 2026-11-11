package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of students. */
public record StudentPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<StudentResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rows that match, in all pages") long total,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The page served, from 0") int page,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The page size served") int size) {
}
