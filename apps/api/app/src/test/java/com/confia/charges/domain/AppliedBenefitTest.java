package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.domain.AppliedBenefit.Source;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One benefit of a charge keeps either the scholarship it came from or the discount (with the assignment of an agreement), never a mix. */
class AppliedBenefitTest {

    private static final UUID AWARD = UUID.randomUUID();
    private static final UUID DISCOUNT = UUID.randomUUID();
    private static final UUID ASSIGNMENT = UUID.randomUUID();
    private static final Money TEN = Money.of("10.00", CurrencyCode.HNL);

    private static AppliedBenefit scholarship(UUID award, String code, UUID discount, String discountCode, UUID assignment,
            Money amount) {
        return new AppliedBenefit(Source.SCHOLARSHIP, award, code, discount, discountCode, assignment, amount);
    }

    private static AppliedBenefit discount(UUID award, String scholarshipCode, UUID discount, String code, UUID assignment,
            Money amount) {
        return new AppliedBenefit(Source.DISCOUNT, award, scholarshipCode, discount, code, assignment, amount);
    }

    @Test
    void aScholarshipBenefitKeepsItsAwardAndCodeAndADiscountBenefitItsDiscountCodeAndAssignment() {
        AppliedBenefit beca = scholarship(AWARD, "BECA-25", null, null, null, TEN);
        AppliedBenefit campaign = discount(null, null, DISCOUNT, "CAMP-10", null, TEN);
        AppliedBenefit agreement = discount(null, null, DISCOUNT, "CONV-10", ASSIGNMENT, TEN);

        assertThat(beca.isScholarship()).isTrue();
        assertThat(beca.code()).isEqualTo("BECA-25");
        assertThat(beca.asOffer()).isEqualTo(new BenefitOffer(AWARD, "BECA-25", TEN));
        assertThat(campaign.isScholarship()).isFalse();
        assertThat(campaign.code()).isEqualTo("CAMP-10");
        assertThat(campaign.asOffer()).isNull();
        assertThat(agreement.assignmentId()).isEqualTo(ASSIGNMENT);
        assertThat(AppliedBenefit.of(new BenefitOffer(AWARD, "BECA-25", TEN))).isEqualTo(beca);
        assertThat(AppliedBenefit.of(new DiscountCandidate(DISCOUNT, "CONV-10", ASSIGNMENT, true, base -> base), TEN))
                .isEqualTo(agreement);
    }

    @Test
    void aScholarshipBenefitWithoutItsAwardOrCodeOrWithAnythingOfADiscountIsRefused() {
        assertThatThrownBy(() -> scholarship(null, "BECA-25", null, null, null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> scholarship(AWARD, null, null, null, null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> scholarship(AWARD, " ", null, null, null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> scholarship(AWARD, "BECA-25", DISCOUNT, null, null, TEN))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> scholarship(AWARD, "BECA-25", null, "CAMP-10", null, TEN))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> scholarship(AWARD, "BECA-25", null, null, ASSIGNMENT, TEN))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void aDiscountBenefitWithoutItsDiscountOrCodeOrWithAnythingOfAScholarshipIsRefused() {
        assertThatThrownBy(() -> discount(null, null, null, "CAMP-10", null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> discount(null, null, DISCOUNT, null, null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> discount(null, null, DISCOUNT, "", null, TEN)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> discount(AWARD, null, DISCOUNT, "CAMP-10", null, TEN))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> discount(null, "BECA-25", DISCOUNT, "CAMP-10", null, TEN))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void aBenefitThatTakesNothingOffIsRefused() {
        assertThatThrownBy(() -> discount(null, null, DISCOUNT, "CAMP-10", null, Money.zero(CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> discount(null, null, DISCOUNT, "CAMP-10", null, Money.of("-1.00", CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
