package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The close of a session: what was declared, what was expected and the difference (declared minus expected). */
public record SessionCloseResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) CashMoney declaredAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED) CashMoney expectedAmount,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Declared minus expected, computed by the server")
        CashMoney differenceAmount,
        @Schema(description = "Present only when there is a difference") String differenceReason,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID closedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant closedAt) {

    @Override
    public String toString() {
        return "SessionCloseResponse[id=" + id + "]";
    }
}
