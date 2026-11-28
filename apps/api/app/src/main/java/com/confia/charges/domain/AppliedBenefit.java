package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One benefit that reduced a charge, as the charge keeps it (docs/cortes/scholarships-discounts.md): where it came from (the award of a
 * scholarship, or a discount with, for an agreement, the assignment that made it apply to the student), its code copied at accrual time
 * and the amount it took off. Nothing here is a scholarship or a discount of the {@code scholarships} domain: only identifiers, a code and
 * an amount cross the boundary.
 */
public record AppliedBenefit(Source source, UUID awardId, String scholarshipCode, UUID discountId, String discountCode,
        UUID assignmentId, Money amount) {

    /** What produced the benefit. */
    public enum Source {
        SCHOLARSHIP, DISCOUNT
    }

    public AppliedBenefit {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(amount, "amount");
        boolean scholarship = source == Source.SCHOLARSHIP;
        boolean shaped = scholarship
                ? awardId != null && scholarshipCode != null && !scholarshipCode.isBlank() && discountId == null
                        && discountCode == null && assignmentId == null
                : discountId != null && discountCode != null && !discountCode.isBlank() && awardId == null
                        && scholarshipCode == null;
        if (!shaped || !amount.isPositive()) {
            throw new InvalidChargesValueException();
        }
    }

    /** The benefit of the scholarship that the offer describes. */
    public static AppliedBenefit of(BenefitOffer offer) {
        return new AppliedBenefit(Source.SCHOLARSHIP, offer.awardId(), offer.scholarshipCode(), null, null, null,
                offer.amount());
    }

    /** The benefit of a discount, for the amount it took off. */
    public static AppliedBenefit of(DiscountCandidate discount, Money amount) {
        return new AppliedBenefit(Source.DISCOUNT, null, null, discount.discountId(), discount.discountCode(),
                discount.assignmentId(), amount);
    }

    public boolean isScholarship() {
        return source == Source.SCHOLARSHIP;
    }

    /** The code of whatever produced the benefit: the scholarship or the discount. */
    public String code() {
        return isScholarship() ? scholarshipCode : discountCode;
    }

    /** The scholarship as the offer it came from, or {@code null} for a discount. */
    public BenefitOffer asOffer() {
        return isScholarship() ? new BenefitOffer(awardId, scholarshipCode, amount) : null;
    }
}
