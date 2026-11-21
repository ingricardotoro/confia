package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One row of the payments map: the account of the chart that the cash or the bank posts to. */
public record PaymentPostingAccountResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CASH", "BANK"}) String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "An account of the chart of the institution")
        UUID ledgerAccountId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Send it back when this row is replaced") long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
