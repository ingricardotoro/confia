package com.confia.imports.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** A rejected row or field: where it is and why, never the rejected value. */
public record RejectionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Line of the file where the row starts "
                + "(the header is line 1)") int line,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Column of the template, or row when the row "
                + "does not have the cells of the header") String field,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"required", "invalid-format", "too-long", "invalid-date", "future-birth-date",
                        "duplicate-in-file", "duplicate-national-id", "already-imported", "invalid-percent", "unknown-student",
                        "unknown-guardian", "student-not-active", "guardian-not-active", "student-already-linked",
                        "group-rejected", "sum-not-100", "multiple-primary-payer", "multiple-billing-recipient",
                        "duplicate-guardian"}) String reason) {
}
