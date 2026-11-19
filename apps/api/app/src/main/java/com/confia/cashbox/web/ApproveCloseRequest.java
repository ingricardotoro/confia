package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The body of {@code POST /api/v1/cashbox/sessions/{id}/approve}: what the approver found. It may name a person. */
public record ApproveCloseRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 1, maxLength = 500)
        @NotBlank @Size(max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String note) {

    @Override
    public String toString() {
        return "ApproveCloseRequest[noteLength=" + (note == null ? 0 : note.length()) + "]";
    }
}
