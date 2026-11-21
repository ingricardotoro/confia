package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One movement of cash. The key of its request never leaves the server; the notes may name a person. */
public record MovementResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID sessionId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"deposit", "withdrawal", "adjustment", "payment_in"},
                description = "payment_in is the cash left by a cash payment (docs/cortes/payments-register.md)")
        String type,
        @Schema(requiredMode = RequiredMode.REQUIRED) CashMoney amount,
        String notes,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID createdBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

    @Override
    public String toString() {
        return "MovementResponse[id=" + id + ", sessionId=" + sessionId + ", type=" + type + "]";
    }
}
