package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The acceptance example of docs/cortes/scholarships-discounts.md at the level of one charge: the scholarship and the discounts of a
 * student reduce the base, the tax of a taxable concept is computed on what is left and the total is exact. Every rate is fictional.
 */
class ChargeEvaluatorDiscountsTest {

    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static UnaryOperator<Money> share(String points) {
        return base -> base.percentageToMinorUnit(Percentage.of(points), RoundingMode.HALF_UP);
    }

    private static DiscountCandidate discount(String code, boolean stackable, String points) {
        return new DiscountCandidate(UUID.randomUUID(), code, null, stackable, share(points));
    }

    private static ComputedCharge evaluate(BenefitOffer scholarship, boolean taxable, DiscountCandidate... discounts) {
        ChargeEvaluator evaluator = new ChargeEvaluator(
                base -> scholarship == null ? List.of() : List.of(scholarship), base -> List.of(discounts));
        TaxRateInfo rate = new TaxRateInfo(UUID.randomUUID(), true, List.of(new TaxRateInfo.Validity(UUID.randomUUID(),
                Percentage.of("15"), LocalDate.parse("2027-01-01"), null)));
        AccrualPeriod period = AccrualPeriods.of(PlanFrequency.MONTHLY, 1, FEBRUARY, 5).get(0);
        return evaluator.evaluate(new StudentPlacement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true),
                UUID.randomUUID(), new AccrualPeriods.PlanPeriod(UUID.randomUUID(), UUID.randomUUID(), period),
                new ConceptTerms(UUID.randomUUID(), true, "TUITION", taxable, taxable ? rate.taxRateId() : null),
                List.of(new PriceCandidate(UUID.randomUUID(), PriceScope.INSTITUTION, null, hnl("1000.00"),
                        LocalDate.parse("2027-01-01"), null)), taxable ? rate : null);
    }

    private static BenefitOffer scholarship20() {
        return new BenefitOffer(UUID.randomUUID(), "BECA-20", hnl("200.00"));
    }

    @Test
    void aScholarshipOf20PercentAndAStackableDiscountOf10PercentLeaveANetBaseOf720AndATaxOf108() {
        // "base neta L 720.00, impuesto L 108.00 y total L 828.00"; the ledger takes 1,000.00 of revenue and 280.00 of benefits.
        ComputedCharge charge = evaluate(scholarship20(), true, discount("CAMP-10", true, "10"));

        assertThat(charge.amounts().base()).isEqualTo(hnl("1000.00"));
        assertThat(charge.amounts().discount()).isEqualTo(hnl("280.00"));
        assertThat(charge.netBase()).isEqualTo(hnl("720.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("108.00"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("828.00"));
        assertThat(charge.benefits()).extracting(AppliedBenefit::code).containsExactly("BECA-20", "CAMP-10");
        assertThat(charge.benefit().scholarshipCode()).isEqualTo("BECA-20");
    }

    @Test
    void aNonStackableDiscountOf25PercentAppliesAloneAndTheNetBaseIs750() {
        ComputedCharge charge = evaluate(scholarship20(), true, discount("CAMP-25", false, "25"));

        assertThat(charge.netBase()).isEqualTo(hnl("750.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("112.50"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("862.50"));
        assertThat(charge.benefits()).extracting(AppliedBenefit::code).containsExactly("CAMP-25");
        assertThat(charge.benefit()).isNull();
    }

    @Test
    void discountsAloneReduceAChargeThatHasNoScholarshipAndALessTaxableConceptCarriesNoTax() {
        ComputedCharge charge = evaluate(null, false, discount("CAMP-10", true, "10"));

        assertThat(charge.benefit()).isNull();
        assertThat(charge.amounts().discount()).isEqualTo(hnl("100.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("0.00"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("900.00"));
    }

    @Test
    void theDiscountOfTheWholeBaseLeavesNothingToPay() {
        ComputedCharge charge = evaluate(null, true, discount("FULL", true, "100"));

        assertThat(charge.netBase()).isEqualTo(hnl("0.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("0.00"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("0.00"));
    }

    @Test
    void theDiscountsAreAskedOnTheBaseOfTheCharge() {
        Money[] asked = new Money[1];
        ChargeEvaluator evaluator = new ChargeEvaluator(base -> List.of(), base -> {
            asked[0] = base;
            return List.of();
        });
        AccrualPeriod period = AccrualPeriods.of(PlanFrequency.MONTHLY, 1, FEBRUARY, 5).get(0);

        evaluator.evaluate(new StudentPlacement(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), true),
                UUID.randomUUID(), new AccrualPeriods.PlanPeriod(UUID.randomUUID(), UUID.randomUUID(), period),
                new ConceptTerms(UUID.randomUUID(), true, "TUITION", false, null),
                List.of(new PriceCandidate(UUID.randomUUID(), PriceScope.INSTITUTION, null, hnl("1000.00"),
                        LocalDate.parse("2027-01-01"), null)), null);

        assertThat(asked[0]).isEqualTo(hnl("1000.00"));
    }
}
