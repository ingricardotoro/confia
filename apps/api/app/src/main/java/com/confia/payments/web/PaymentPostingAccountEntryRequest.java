package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** One entry of {@code PUT /api/v1/payments/posting-accounts}: the account of the cash or of the bank. */
public record PaymentPostingAccountEntryRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CASH", "BANK"})
        @NotNull @Pattern(regexp = "CASH|BANK") String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "An active asset account of the chart of the institution that carries no balance of a student")
        @NotNull UUID ledgerAccountId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, minimum = "0",
                description = "The version of the row the editor read; absent when the entry was never set. A "
                        + "different one answers 409 stale-version")
        @Min(0) Long version) {
}
