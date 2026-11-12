package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The current guardians of a student with the exact total of their percentages, computed by the server: 100.00 when
 * the student is assigned and 0.00 when it has no guardian yet.
 */
public record StudentGuardiansResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", maximum = "100",
                description = "The exact sum of the percentages of the links, two decimals") BigDecimal totalPercent,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<StudentGuardianResponse> links) {

    /** Spring logs the object it writes at DEBUG: the names of the guardians never reach a log through it. */
    @Override
    public String toString() {
        return "StudentGuardiansResponse[studentId=" + studentId + "]";
    }
}
