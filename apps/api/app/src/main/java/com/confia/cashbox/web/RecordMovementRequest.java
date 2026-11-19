package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/cashbox/sessions/{id}/movements}. A deposit and a withdrawal carry a positive amount; an
 * adjustment carries a signed amount that is not zero and notes of 20 to 500 characters. The notes may name a person: they never
 * reach a log through {@code toString}.
 */
public record RecordMovementRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"deposit", "withdrawal", "adjustment"})
        @NotNull @Pattern(regexp = "^(deposit|withdrawal|adjustment)$") String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull @Valid CashMoney amount,
        @Schema(maxLength = 500, description = "Required for an adjustment (20 to 500 characters)")
        @Size(max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String notes) {

    @Override
    public String toString() {
        return "RecordMovementRequest[type=" + type + ", notesLength=" + (notes == null ? 0 : notes.length()) + "]";
    }
}
