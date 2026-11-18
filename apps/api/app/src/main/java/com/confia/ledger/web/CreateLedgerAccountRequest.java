package com.confia.ledger.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/ledger/accounts}. The institution is the one of the token and is never a member of the
 * body. The code, the type and the student flag cannot be changed after creation.
 */
public record CreateLedgerAccountRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 30,
                description = "The code of the account in the chart; unique in the institution whatever the capitals")
        @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_.-]{0,29}$") String code,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"})
        @NotBlank @Pattern(regexp = "ASSET|LIABILITY|EQUITY|INCOME|EXPENSE") String type,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "True for the account that carries the balance of each student (the receivable); only an "
                        + "ASSET or a LIABILITY may")
        @NotNull Boolean holdsStudentBalances) {
}
