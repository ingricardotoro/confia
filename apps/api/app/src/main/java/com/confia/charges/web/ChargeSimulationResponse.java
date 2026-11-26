package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the accrual of a period would produce for one student, by concept, with nothing written: the base, the scholarship applied, the
 * net base, the tax and the total. It is the result of the same code the accrual engine runs. Every amount is rounded by the server to
 * the minor unit of its currency.
 */
public record ChargeSimulationResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID planId,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The period as YYYY-MM") String periodLabel,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Line> lines) {

    /**
     * One concept of the period. The amounts are present only when the outcome is {@code CREATED}; a charge that no scholarship
     * reduces has a benefit of zero and no award.
     */
    public record Line(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"CREATED", "ALREADY_ACCRUED", "ALREADY_ACCRUED_OTHER_PLAN", "ERROR"})
            String outcome,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String errorCode,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney base,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "What the scholarship takes off the base; zero when none applies") AccrualMoney benefitAmount,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The base minus the benefit: what the tax is computed on") AccrualMoney netBase,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The award that gives the benefit; absent when no scholarship applies") UUID scholarshipAwardId,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The code of the scholarship of the award; absent when no scholarship applies")
            String scholarshipCode,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney tax,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney total,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID existingChargeId) {
    }
}
