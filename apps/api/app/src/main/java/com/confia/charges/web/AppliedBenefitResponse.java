package com.confia.charges.web;

import com.confia.charges.application.AppliedBenefitView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/**
 * One benefit that reduced a charge (docs/cortes/scholarships-discounts.md): where it came from, its code and what it took off. The
 * charge lists them in the order they were applied: the scholarship first, then the discounts of the cascade. The amount is rounded by
 * the server to the minor unit of its currency.
 */
@Schema(name = "AppliedBenefit", description = "A benefit that reduced a charge, in the order it was applied")
public record AppliedBenefitResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"SCHOLARSHIP", "DISCOUNT"}) String source,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The code of the scholarship or of the discount, as it was when the charge was issued") String code,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The award of the scholarship; present only for a scholarship") UUID scholarshipAwardId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The discount; present only for a discount") UUID discountId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The approved assignment that applied an agreement discount; absent for a campaign discount")
        UUID discountAssignmentId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "What the benefit took off the charge") AccrualMoney amount) {

    static AppliedBenefitResponse of(AppliedBenefitView benefit) {
        return new AppliedBenefitResponse(benefit.source(), benefit.code(), benefit.awardId(), benefit.discountId(),
                benefit.assignmentId(), AccrualMoney.of(benefit.amount()));
    }

    static List<AppliedBenefitResponse> of(List<AppliedBenefitView> benefits) {
        return benefits.stream().map(AppliedBenefitResponse::of).toList();
    }
}
