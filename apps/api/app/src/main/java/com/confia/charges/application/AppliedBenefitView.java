package com.confia.charges.application;

import com.confia.charges.domain.AppliedBenefit;
import com.confia.kernel.Money;
import java.util.List;
import java.util.UUID;

/**
 * One benefit that reduced a charge, as the preview, the simulator and the charges of a student show it (docs/cortes/
 * scholarships-discounts.md): its source ({@code SCHOLARSHIP} or {@code DISCOUNT}), the code copied when the charge was issued, the award,
 * the discount and the approved assignment that applied it (each present only when it is the source), and the exact amount it took off.
 * The web layer rounds the amount for presentation.
 */
public record AppliedBenefitView(String source, UUID awardId, String scholarshipCode, UUID discountId, String discountCode,
        UUID assignmentId, Money amount) {

    /** The code of the scholarship or of the discount. */
    public String code() {
        return scholarshipCode != null ? scholarshipCode : discountCode;
    }

    public static AppliedBenefitView of(AppliedBenefit benefit) {
        return new AppliedBenefitView(benefit.source().name(), benefit.awardId(), benefit.scholarshipCode(),
                benefit.discountId(), benefit.discountCode(), benefit.assignmentId(), benefit.amount());
    }

    public static List<AppliedBenefitView> of(List<AppliedBenefit> benefits) {
        return benefits.stream().map(AppliedBenefitView::of).toList();
    }
}
