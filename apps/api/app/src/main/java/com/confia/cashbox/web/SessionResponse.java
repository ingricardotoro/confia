package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A cash session with its movements, its close and its approval (the base of the printable report of the close). */
public record SessionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID cashierId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"open", "pending_approval", "closed"})
        String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) CashMoney openingFloat,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The expected cash, computed by the server: float plus deposits, minus withdrawals, plus adjustments")
        CashMoney expectedAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<MovementResponse> movements,
        @Schema(description = "Present once the session was closed") SessionCloseResponse close,
        @Schema(description = "Present once the close with a difference was approved") SessionApprovalResponse approval,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant openedAt) {
}
