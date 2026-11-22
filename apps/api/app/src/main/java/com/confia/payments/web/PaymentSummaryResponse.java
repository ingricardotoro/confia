package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A payment as a list shows it. The notes are not part of it. */
public record PaymentSummaryResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CASH", "BANK_TRANSFER"}) String method,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"declared", "confirmed", "rejected"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) PaymentMoney amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate receivedOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String reference,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String bankName,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID registeredBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant registeredAt) {

    @Override
    public String toString() {
        return "PaymentSummaryResponse[id=" + id + ", method=" + method + ", status=" + status + "]";
    }
}
