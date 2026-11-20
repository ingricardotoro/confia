package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One row of the list of sessions; the amounts of the close are present once the session was closed. */
public record SessionSummaryResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID cashierId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"open", "pending_approval", "closed"})
        String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) CashMoney openingFloat,
        CashMoney declaredAmount,
        CashMoney expectedAmount,
        CashMoney differenceAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant openedAt,
        Instant closedAt,
        Instant approvedAt) {
}
