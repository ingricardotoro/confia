package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One row of the map of posting accounts: the account of the chart a purpose (and, for the revenue, a category) posts to. */
public record PostingAccountResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"RECEIVABLE", "REVENUE", "TAX_PAYABLE"})
        String purpose,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"},
                description = "Only for the revenue; absent is the default revenue account of every category without its own")
        String conceptCategory,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "An account of the chart of the institution")
        UUID ledgerAccountId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Send it back when this row is replaced") long version,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
}
