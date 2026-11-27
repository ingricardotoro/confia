package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an accrual would do, with nothing written: counts, exact totals by currency, errors by code, up to 500 rows, what
 * would make a run refuse and the digest to send back when the user confirms.
 */
public record AccrualPreviewResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID planId,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date") LocalDate periodStart,
        @Schema(requiredMode = RequiredMode.REQUIRED) int studentsEvaluated,
        @Schema(requiredMode = RequiredMode.REQUIRED) int toCreate,
        @Schema(requiredMode = RequiredMode.REQUIRED) int alreadyAccrued,
        @Schema(requiredMode = RequiredMode.REQUIRED) int alreadyAccruedOtherPlan,
        @Schema(requiredMode = RequiredMode.REQUIRED) int errors,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Total> totals,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Error code to how many charges")
        Map<String, Integer> errorsByCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether there are more rows than the 500 listed")
        boolean itemsTruncated,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Why a run would be rejected now: plan-not-active, posting-accounts-incomplete or "
                        + "plan-target-outside-year") List<String> blockers,
        @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String previewDigest) {

    /** What the charges to create add up to in one currency (exact sums, in the minor unit). */
    public record Total(
            @Schema(requiredMode = RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = RequiredMode.REQUIRED) int charges,
            @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney base,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "What the scholarships take off the bases")
            AccrualMoney benefitAmount,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The bases minus the benefits") AccrualMoney netBase,
            @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney tax,
            @Schema(requiredMode = RequiredMode.REQUIRED) AccrualMoney total) {
    }

    /** One student and concept; the amounts are present only for a charge to create. */
    public record Item(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"CREATED", "ALREADY_ACCRUED", "ALREADY_ACCRUED_OTHER_PLAN", "ERROR"})
            String outcome,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String errorCode,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney base,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "What the benefits (scholarship and discounts) take off the base; zero when none applies") AccrualMoney benefitAmount,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The base minus the benefit: what the tax is computed on") AccrualMoney netBase,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney tax,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) AccrualMoney total,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The award that gives the benefit; absent when no scholarship applies") UUID scholarshipAwardId,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The code of the scholarship of the award; absent when no scholarship applies")
            String scholarshipCode,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) UUID existingChargeId,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Every benefit that reduces the charge, scholarship and discounts, in the order they apply; empty when none")
            List<AppliedBenefitResponse> benefits) {
    }
}
