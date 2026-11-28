package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The accumulation rule of INV-15 (docs/cortes/scholarships-discounts.md): the cascade when every benefit is stackable, only the
 * greatest benefit when any discount is not, the order of the cascade, the floor at zero, the tie-break and the rounding at each step.
 * The functions of the discounts are the rule of the scholarships capability (the share or the fixed amount, {@code HALF_UP} once at
 * two decimals, never above the base): they are written here exactly as that rule is.
 */
class BenefitStackingTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static BenefitOffer scholarship(String code, String amount) {
        return new BenefitOffer(UUID.randomUUID(), code, hnl(amount));
    }

    private static UnaryOperator<Money> share(String points) {
        return base -> base.percentageToMinorUnit(Percentage.of(points), RoundingMode.HALF_UP);
    }

    private static UnaryOperator<Money> fixed(String amount) {
        return base -> hnl(amount).isGreaterThan(base) ? base : hnl(amount);
    }

    private static DiscountCandidate discount(String code, boolean stackable, UnaryOperator<Money> benefit) {
        return new DiscountCandidate(UUID.randomUUID(), code, null, stackable, benefit);
    }

    private static List<String> amounts(List<AppliedBenefit> applied) {
        return applied.stream().map(benefit -> benefit.amount().toPlainString()).toList();
    }

    @Test
    void aScholarshipAndAStackableDiscountApplyInCascadeAndTheScholarshipComesFirst() {
        // S2: base 1,000.00, 20 % scholarship and 10 % stackable campaign: 1,000.00 -> 800.00 -> 720.00.
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("1000.00"), Optional.of(scholarship("BECA-20", "200.00")),
                List.of(discount("CAMP-10", true, share("10"))));

        assertThat(applied).extracting(AppliedBenefit::source)
                .containsExactly(AppliedBenefit.Source.SCHOLARSHIP, AppliedBenefit.Source.DISCOUNT);
        assertThat(amounts(applied)).containsExactly("200.0000", "80.0000");
        assertThat(applied.get(1).discountCode()).isEqualTo("CAMP-10");
    }

    @Test
    void aNonStackableDiscountOf25PercentWinsAloneOverTheScholarshipOf20Percent() {
        // S2: "Si el descuento no es acumulable y es del 25 %, se aplica solo el descuento: base neta L 750.00."
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("1000.00"), Optional.of(scholarship("BECA-20", "200.00")),
                List.of(discount("CAMP-25", false, share("25"))));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).isScholarship()).isFalse();
        assertThat(applied.get(0).amount()).isEqualTo(hnl("250.00"));
    }

    @Test
    void aNonStackableDiscountSmallerThanTheScholarshipLosesAndTheScholarshipAppliesAlone() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("1000.00"), Optional.of(scholarship("BECA-20", "200.00")),
                List.of(discount("CAMP-5", false, share("5")), discount("CAMP-6", true, share("6"))));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).isScholarship()).isTrue();
    }

    @Test
    void whenAnyDiscountIsNotStackableTheStackableOnesDoNotAddUpEither() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("1000.00"), Optional.empty(),
                List.of(discount("A-STACK", true, share("10")), discount("B-ALONE", false, share("12"))));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).discountCode()).isEqualTo("B-ALONE");
        assertThat(applied.get(0).amount()).isEqualTo(hnl("120.00"));
    }

    @Test
    void twoStackableDiscountsApplyInTheOrderTheyComeInWithThePercentageFirst() {
        // Percentage before fixed amount: 10 % of 1,000.00 = 100.00, then L 50.00 fixed = 50.00.
        List<AppliedBenefit> percentFirst = BenefitStacking.apply(hnl("1000.00"), Optional.empty(),
                List.of(discount("A-PCT", true, share("10")), discount("B-FIX", true, fixed("50.00"))));
        // The other order would give another total: the port delivers the cascade order and the rule keeps it.
        List<AppliedBenefit> fixedFirst = BenefitStacking.apply(hnl("1000.00"), Optional.empty(),
                List.of(discount("B-FIX", true, fixed("50.00")), discount("A-PCT", true, share("10"))));

        assertThat(amounts(percentFirst)).containsExactly("100.0000", "50.0000");
        assertThat(amounts(fixedFirst)).containsExactly("50.0000", "95.0000");
    }

    @Test
    void benefitsThatWouldAddUpToMoreThanTheBaseLeaveItAtZero() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("100.00"), Optional.of(scholarship("BECA-80", "80.00")),
                List.of(discount("A-FIX", true, fixed("50.00")), discount("B-FIX", true, fixed("30.00"))));

        // 80.00 leaves 20.00: the first fixed discount gives what is left and the second one gives nothing, so it leaves no line.
        assertThat(amounts(applied)).containsExactly("80.0000", "20.0000");
        assertThat(applied).extracting(AppliedBenefit::code).containsExactly("BECA-80", "A-FIX");
    }

    @Test
    void aScholarshipOfTheWholeBaseLeavesNothingToTheDiscounts() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("100.00"), Optional.of(scholarship("BECA-100", "100.00")),
                List.of(discount("CAMP-10", true, share("10"))));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).isScholarship()).isTrue();
    }

    @Test
    void aTieBetweenTheScholarshipAndANonStackableDiscountGoesToTheScholarship() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("1000.00"), Optional.of(scholarship("ZZZ", "250.00")),
                List.of(discount("AAA", false, share("25"))));

        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).isScholarship()).isTrue();
    }

    @Test
    void aTieBetweenNonStackableDiscountsGoesToTheLowestCodeWhateverTheOrder() {
        DiscountCandidate low = discount("A-LOW", false, share("10"));
        DiscountCandidate high = discount("B-HIGH", false, share("10"));

        assertThat(BenefitStacking.apply(hnl("1000.00"), Optional.empty(), List.of(high, low)).get(0).discountCode())
                .isEqualTo("A-LOW");
        assertThat(BenefitStacking.apply(hnl("1000.00"), Optional.empty(), List.of(low, high)).get(0).discountCode())
                .isEqualTo("A-LOW");
    }

    @Test
    void theRoundingIsHalfUpAtEachStepOfTheCascade() {
        // 33.33 * 10 % = 3.333 -> 3.33; then 30.00 * 15 % = 4.50 exactly; and 10.05 * 50 % = 5.025 -> 5.03 (HALF_UP, not HALF_EVEN).
        assertThat(amounts(BenefitStacking.apply(hnl("33.33"), Optional.empty(),
                List.of(discount("A", true, share("10")), discount("B", true, share("10"))))))
                .containsExactly("3.3300", "3.0000");
        assertThat(amounts(BenefitStacking.apply(hnl("10.05"), Optional.empty(), List.of(discount("A", true, share("50"))))))
                .containsExactly("5.0300");
    }

    @Test
    void aDiscountThatGivesNothingOnTheBaseLeavesNoLineAndDoesNotBlockTheOthers() {
        List<AppliedBenefit> applied = BenefitStacking.apply(hnl("0.04"), Optional.empty(),
                List.of(discount("TINY", false, share("10")), discount("OTHER", true, share("50"))));

        // 10 % of 0.04 is 0.004 -> 0.00 (nothing, so it does not count as a non-stackable one); 50 % is 0.02.
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).code()).isEqualTo("OTHER");
        assertThat(applied.get(0).amount()).isEqualTo(hnl("0.02"));
    }

    @Test
    void withoutBenefitsNothingApplies() {
        assertThat(BenefitStacking.apply(hnl("1000.00"), Optional.empty(), List.of())).isEmpty();
        assertThat(BenefitStacking.apply(hnl("1000.00"), Optional.of(scholarship("Z", "0.00")), List.of())).isEmpty();
    }

    @Test
    void aScholarshipAboveTheBaseOrADiscountFunctionAboveItIsADefect() {
        assertThatThrownBy(() -> BenefitStacking.apply(hnl("100.00"), Optional.of(scholarship("BIG", "100.01")), List.of()))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> BenefitStacking.apply(hnl("100.00"), Optional.empty(),
                List.of(discount("BAD", true, base -> base.add(hnl("0.01"))))))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theAppliedBenefitKeepsWhereItCameFrom() {
        UUID assignment = UUID.randomUUID();
        DiscountCandidate agreement = new DiscountCandidate(UUID.randomUUID(), "CONV-1", assignment, true, share("10"));

        AppliedBenefit applied = BenefitStacking.apply(hnl("1000.00"), Optional.empty(), List.of(agreement)).get(0);

        assertThat(applied.discountId()).isEqualTo(agreement.discountId());
        assertThat(applied.assignmentId()).isEqualTo(assignment);
        assertThat(applied.awardId()).isNull();
        assertThat(applied.asOffer()).isNull();
    }

    @Test
    void anAppliedBenefitHasTheShapeOfItsSourceAndAPositiveAmount() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new AppliedBenefit(AppliedBenefit.Source.SCHOLARSHIP, id, "C", id, "D", null, hnl("1.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AppliedBenefit(AppliedBenefit.Source.DISCOUNT, null, null, null, "D", null, hnl("1.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AppliedBenefit(AppliedBenefit.Source.DISCOUNT, null, null, id, "D", null, hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AppliedBenefit(AppliedBenefit.Source.DISCOUNT, null, null, id, " ", null, hnl("1.00")))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
