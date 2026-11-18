package com.confia.ledger.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/ledger/accounts/{id}}: the name and the active flag, replaced as a whole, and the version
 * the editor read. It has no code, type nor student flag: they are immutable, and a body that carries one is refused as an
 * unknown member.
 */
public record UpdateLedgerAccountRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0",
                description = "The version of the account the editor read; a different one answers 409 stale-version")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 120)
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String name,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "False deactivates the account (it refuses new facts, its entries stay); an account that "
                        + "the posting map points at cannot be deactivated")
        @NotNull Boolean active) {
}
