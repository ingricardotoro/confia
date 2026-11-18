package com.confia.charges.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.UUID;

/**
 * One discrepancy the nightly check confirmed. The charge, the transaction and the amounts are present only when the kind of
 * discrepancy is about them; an amount is rounded by the server and travels with its currency.
 */
public record IntegrityFindingResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"UNBALANCED_TRANSACTION", "CHARGE_POSTING_MISSING", "CHARGE_POSTING_DUPLICATE",
                        "CHARGE_TOTAL_MISMATCH", "VOID_REVERSAL_MISSING", "VOID_REVERSAL_DUPLICATE",
                        "REVERSAL_WITHOUT_VOID", "ORPHAN_POSTING", "RECEIVABLE_MISMATCH"})
        String kind,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_NULL) UUID chargeId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_NULL)
        UUID ledgerTransactionId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "What the records of the charges say")
        @JsonInclude(JsonInclude.Include.NON_NULL) AccrualMoney expected,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "What the ledger holds")
        @JsonInclude(JsonInclude.Include.NON_NULL) AccrualMoney actual) {
}
