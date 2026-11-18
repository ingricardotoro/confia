package com.confia.charges.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A charge of a student, as the API answers it. {@code status} is derived: {@code VOIDED} when a void record exists. Every
 * amount is rounded by the server to the minor unit of its currency. {@code voided} is absent for an active charge.
 */
public record StudentChargeResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptCode,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String conceptCategory,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The period as YYYY-MM") String periodLabel,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodEnd,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate dueOn,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"AUTOMATIC", "MANUAL"}) String origin,
        @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney base,
        @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney tax,
        @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney total,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "VOIDED"}) String status,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Present only when the charge is voided")
        @JsonInclude(JsonInclude.Include.NON_NULL) VoidInfo voided) {

    /** What a void left on the charge. The reason may name a person: it never reaches a log through this record. */
    public record VoidInfo(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID voidId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID reversalTransactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant voidedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) String reason) {

        @Override
        public String toString() {
            return "VoidInfo[voidId=" + voidId + "]";
        }
    }
}
