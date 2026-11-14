package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The body of {@code PUT /api/v1/students/{studentId}/guardians}: the complete set of the guardians of the student.
 * An empty list removes every guardian (a student not yet assigned is valid); otherwise the percentages add up to
 * exactly 100. An unknown member is refused by the JSON mapper.
 */
public record ReplaceStudentGuardiansRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20,
                description = "The whole set of links; the current ones that differ are closed and new ones open")
        @NotNull @Size(max = 20) List<@NotNull @Valid GuardianLinkRequest> links) {
}
