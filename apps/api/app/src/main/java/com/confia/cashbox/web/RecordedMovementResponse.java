package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/** A recorded movement with the expected cash of its session after it, computed by the server. */
public record RecordedMovementResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) MovementResponse movement,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The expected cash of the session after the movement")
        CashMoney expectedAfter) {
}
