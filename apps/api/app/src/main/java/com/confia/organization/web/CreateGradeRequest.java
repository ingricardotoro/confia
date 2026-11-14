package com.confia.organization.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** The body of {@code POST /api/v1/organization/grades}; the modality must be active. */
public record CreateGradeRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The modality of the grade; fixed afterwards")
        @NotNull UUID modalityId,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120, description = "Name, unique in the modality")
        @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "Order, unique in the modality")
        @NotNull @PositiveOrZero Integer ordinal) {
}
