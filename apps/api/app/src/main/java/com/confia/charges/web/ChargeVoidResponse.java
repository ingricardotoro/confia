package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The void of a charge, as the API answers it. The key of its request never leaves the server. */
public record ChargeVoidResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID chargeId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"VOIDED"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The REVERSAL transaction of the ledger that mirrors the transaction of the charge")
        UUID reversalTransactionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String reason,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The staff account that voided the charge")
        UUID voidedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant voidedAt) {

    /** The reason is free text that may name a person: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "ChargeVoidResponse[id=" + id + ", chargeId=" + chargeId + "]";
    }
}
