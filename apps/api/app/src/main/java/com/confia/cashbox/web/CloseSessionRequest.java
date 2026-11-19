package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/cashbox/sessions/{id}/close}: the physical count and, when it differs from the expected cash,
 * the reason (20 to 500 characters). The reason may name a person: it never reaches a log through {@code toString}.
 */
public record CloseSessionRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The counted cash: zero or more, at most two decimals")
        @NotNull @Valid CashMoney declaredAmount,
        @Schema(maxLength = 500, description = "Required when the count differs from the expected cash (20 to 500 characters)")
        @Size(max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String differenceReason) {

    @Override
    public String toString() {
        return "CloseSessionRequest[reasonLength=" + (differenceReason == null ? 0 : differenceReason.length()) + "]";
    }
}
