package com.confia.payments.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment with its state, the decision on it, the charges it was applied to and what stayed as a credit. Every amount is
 * rounded by the server to the minor unit. Only a confirmed payment has allocations; a declared transfer and a rejected one show
 * no allocations and zero allocated and unallocated. The notes, the reference and the reason never reach a log through {@code
 * toString}.
 */
public record PaymentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String studentCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID guardianId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CASH", "BANK_TRANSFER"}) String method,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"declared", "confirmed", "rejected"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) PaymentMoney amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate receivedOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String reference,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String bankName,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String notes,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The cash session of a cash payment") UUID cashSessionId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The ledger transaction that posted it; absent while a transfer is declared or when it was rejected")
        UUID ledgerTransactionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID registeredBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant registeredAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Present once a transfer was decided")
        @JsonInclude(JsonInclude.Include.NON_NULL) DecisionResponse decision,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<AllocationResponse> allocations,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The sum applied to charges") PaymentMoney allocated,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "What stayed credited in the receivable of the student after every pending charge was covered")
        PaymentMoney unallocated) {

    @Override
    public String toString() {
        return "PaymentResponse[id=" + id + ", studentId=" + studentId + ", method=" + method + ", status=" + status + "]";
    }
}
