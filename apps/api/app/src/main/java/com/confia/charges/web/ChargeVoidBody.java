package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/charges/{chargeId}/void}: why the charge is voided. The institution is the one of the token
 * and the charge is the one of the path; neither is a member of the body.
 */
public record ChargeVoidBody(
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 20, maxLength = 500,
                description = "Why the charge is voided, from 20 to 500 characters")
        @NotBlank @Size(min = 20, max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String reason) {

    /** The reason is free text that may name a person: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "ChargeVoidBody[reasonLength=" + (reason == null ? 0 : reason.length()) + "]";
    }
}
