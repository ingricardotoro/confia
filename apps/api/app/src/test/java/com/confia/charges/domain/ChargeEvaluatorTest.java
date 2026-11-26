package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * The financial rules of one charge: tax by date with ONE rounding, the errors of the charge that are never a rate of
 * zero, an exact total, and the rules of the student and of the concept. Every rate here is fictional.
 */
class ChargeEvaluatorTest {

    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID GRADE = UUID.randomUUID();
    private static final UUID MODALITY = UUID.randomUUID();
    private static final ChargeEvaluator EVALUATOR = ChargeEvaluator.withoutBenefits();

    private static StudentPlacement student(boolean active) {
        return new StudentPlacement(UUID.randomUUID(), UUID.randomUUID(), GRADE, MODALITY, active);
    }

    private static AccrualPeriods.PlanPeriod period() {
        AccrualPeriod period = AccrualPeriods.of(PlanFrequency.MONTHLY, 1, FEBRUARY, 5).get(0);
        return new AccrualPeriods.PlanPeriod(UUID.randomUUID(), UUID.randomUUID(), period);
    }

    private static ConceptTerms concept(boolean active, boolean taxable, UUID rate) {
        return new ConceptTerms(UUID.randomUUID(), active, "TUITION", taxable, rate);
    }

    private static List<PriceCandidate> price(String amount) {
        return List.of(new PriceCandidate(UUID.randomUUID(), PriceScope.INSTITUTION, null,
                Money.of(amount, CurrencyCode.HNL), LocalDate.parse("2027-01-01"), null));
    }

    private static TaxRateInfo rate(boolean active, String percent, String from, String to) {
        return new TaxRateInfo(UUID.randomUUID(), active, List.of(new TaxRateInfo.Validity(UUID.randomUUID(),
                Percentage.of(percent), LocalDate.parse(from), to == null ? null : LocalDate.parse(to))));
    }

    private static ComputedCharge evaluate(StudentPlacement student, ConceptTerms concept, List<PriceCandidate> prices,
            TaxRateInfo rate) {
        return EVALUATOR.evaluate(student, PLAN, period(), concept, prices, rate);
    }

    private static void assertRefused(Runnable evaluation, AccrualErrorCode code) {
        assertThatThrownBy(evaluation::run).isInstanceOf(AccrualFailure.class)
                .extracting(e -> ((AccrualFailure) e).errorCode()).isEqualTo(code);
    }

    @Test
    void aNonTaxableConceptHasZeroTaxAndNoRateReferences() {
        ComputedCharge charge = evaluate(student(true), concept(true, false, null), price("1500.00"), null);

        assertThat(charge.amounts().base()).isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
        assertThat(charge.amounts().discount().isZero()).isTrue();
        assertThat(charge.amounts().tax().isZero()).isTrue();
        assertThat(charge.amounts().total()).isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
        assertThat(charge.taxable()).isFalse();
        assertThat(charge.taxRateId()).isNull();
    }

    @Test
    void aTaxableConceptAddsTheTaxToTheTotalExactly() {
        TaxRateInfo rate = rate(true, "10", "2027-01-01", null);

        ComputedCharge charge = evaluate(student(true), concept(true, true, rate.taxRateId()), price("1500.00"), rate);

        assertThat(charge.amounts().tax()).isEqualTo(Money.of("150.00", CurrencyCode.HNL));
        assertThat(charge.amounts().total()).isEqualTo(Money.of("1650.00", CurrencyCode.HNL));
        assertThat(charge.taxRateId()).isEqualTo(rate.taxRateId());
        assertThat(charge.taxRatePeriodId()).isEqualTo(rate.validities().get(0).periodId());
        assertThat(charge.taxPercent()).isEqualTo(Percentage.of("10"));
    }

    @Test
    void theMidpointOfTheTaxRoundsUpWithASingleRounding() {
        TaxRateInfo rate = rate(true, "10", "2027-01-01", null);

        // 0.05 at 10 % is 0.005: HALF_UP gives 0.01.
        ComputedCharge charge = evaluate(student(true), concept(true, true, rate.taxRateId()), price("0.05"), rate);

        assertThat(charge.amounts().tax()).isEqualTo(Money.of("0.01", CurrencyCode.HNL));
        assertThat(charge.amounts().total()).isEqualTo(Money.of("0.06", CurrencyCode.HNL));
    }

    @Test
    void aTaxThatIsBelowHalfAMinorUnitRoundsToZeroNotToOneCentByDoubleRounding() {
        TaxRateInfo rate = rate(true, "0.4995", "2027-01-01", null);

        // 1.00 at 0.4995 % is 0.004995: a single rounding gives 0.00 (two roundings would give 0.01).
        ComputedCharge charge = evaluate(student(true), concept(true, true, rate.taxRateId()), price("1.00"), rate);

        assertThat(charge.amounts().tax().isZero()).isTrue();
        assertThat(charge.amounts().total()).isEqualTo(Money.of("1.00", CurrencyCode.HNL));
        assertThat(charge.taxable()).isTrue();
    }

    @Test
    void aTaxableConceptWithoutARateIsAnErrorNeverZeroPercent() {
        assertRefused(() -> evaluate(student(true), concept(true, true, null), price("1500.00"), null),
                AccrualErrorCode.TAX_RATE_MISSING);
    }

    @Test
    void anInactiveRateIsAnError() {
        TaxRateInfo rate = rate(false, "10", "2027-01-01", null);

        assertRefused(() -> evaluate(student(true), concept(true, true, rate.taxRateId()), price("1500.00"), rate),
                AccrualErrorCode.TAX_RATE_INACTIVE);
    }

    @Test
    void aRateWithoutValidityAtThePricingDateIsAnErrorAndTheBoundaryDatesCover() {
        TaxRateInfo ended = rate(true, "10", "2026-01-01", "2027-01-31");
        TaxRateInfo startsLater = rate(true, "10", "2027-02-02", null);

        assertRefused(() -> evaluate(student(true), concept(true, true, ended.taxRateId()), price("1500.00"), ended),
                AccrualErrorCode.TAX_RATE_NOT_IN_FORCE);
        assertRefused(() -> evaluate(student(true), concept(true, true, startsLater.taxRateId()), price("1500.00"),
                startsLater), AccrualErrorCode.TAX_RATE_NOT_IN_FORCE);
        TaxRateInfo startsOnTheDate = rate(true, "10", "2027-02-01", null);
        TaxRateInfo endsOnTheDate = rate(true, "10", "2026-01-01", "2027-02-01");
        assertThat(evaluate(student(true), concept(true, true, startsOnTheDate.taxRateId()), price("100.00"),
                startsOnTheDate).amounts().tax()).isEqualTo(Money.of("10.00", CurrencyCode.HNL));
        assertThat(evaluate(student(true), concept(true, true, endsOnTheDate.taxRateId()), price("100.00"),
                endsOnTheDate).amounts().tax()).isEqualTo(Money.of("10.00", CurrencyCode.HNL));
    }

    @Test
    void anInactiveConceptAndAnInactiveStudentAreErrors() {
        assertRefused(() -> evaluate(student(true), concept(false, false, null), price("1500.00"), null),
                AccrualErrorCode.CONCEPT_INACTIVE);
        assertRefused(() -> evaluate(student(false), concept(true, false, null), price("1500.00"), null),
                AccrualErrorCode.STUDENT_INACTIVE);
    }

    @Test
    void noPriceIsAnErrorOfTheChargeNeverAZeroCharge() {
        assertRefused(() -> evaluate(student(true), concept(true, false, null), List.of(), null),
                AccrualErrorCode.NO_PRICE);
    }

    private static BenefitOffer offer(String code, String amount) {
        return new BenefitOffer(UUID.randomUUID(), code, Money.of(amount, CurrencyCode.HNL));
    }

    private static ChargeEvaluator offering(BenefitOffer... offers) {
        return new ChargeEvaluator(base -> List.of(offers));
    }

    private static ComputedCharge withBenefit(ChargeEvaluator evaluator, String basePrice, boolean taxable) {
        TaxRateInfo rate = rate(true, "15", "2027-01-01", null);
        return evaluator.evaluate(student(true), PLAN, period(), concept(true, taxable, taxable ? rate.taxRateId() : null),
                price(basePrice), taxable ? rate : null);
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    @Test
    void aBenefitThatExceedsTheBaseIsRefusedAndAValidOneReducesTheTaxableBase() {
        ChargeEvaluator tooMuch = new ChargeEvaluator(base -> List.of(new BenefitOffer(UUID.randomUUID(), "B",
                base.add(Money.of("0.01", CurrencyCode.HNL)))));
        assertThatThrownBy(() -> tooMuch.evaluate(student(true), PLAN, period(), concept(true, false, null),
                price("100.00"), null)).isInstanceOf(InvalidChargesValueException.class);

        ComputedCharge charge = withBenefit(offering(offer("B", "10.00")), "100.00", true);

        assertThat(charge.amounts().tax()).isEqualTo(hnl("13.50"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("103.50"));
    }

    @Test
    void aScholarshipOfAQuarterOfAThousandOneHundredLeavesANetBaseOf825AndATaxOf12375() {
        BenefitOffer quarter = offer("BECA-25", "275.00");

        ComputedCharge charge = withBenefit(offering(quarter), "1100.00", true);

        assertThat(charge.amounts().base()).isEqualTo(hnl("1100.00"));
        assertThat(charge.amounts().discount()).isEqualTo(hnl("275.00"));
        assertThat(charge.netBase()).isEqualTo(hnl("825.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("123.75"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("948.75"));
        assertThat(charge.benefit()).isEqualTo(quarter);
    }

    @Test
    void theTaxOfTheReducedBaseIsRoundedOnceHalfUp() {
        // 15 % of 33.50 is 5.025, which rounds up to 5.03; the tax of the gross base would be 15.00.
        ComputedCharge charge = withBenefit(offering(offer("B", "66.50")), "100.00", true);

        assertThat(charge.netBase()).isEqualTo(hnl("33.50"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("5.03"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("38.53"));
    }

    @Test
    void aBenefitThatTakesTheWholeBaseLeavesNothingToPayAndNoTax() {
        ComputedCharge charge = withBenefit(offering(offer("B-100", "1100.00")), "1100.00", true);

        assertThat(charge.netBase()).isEqualTo(hnl("0.00"));
        assertThat(charge.amounts().tax()).isEqualTo(hnl("0.00"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("0.00"));
        assertThat(charge.benefit().scholarshipCode()).isEqualTo("B-100");
    }

    @Test
    void aBenefitOfZeroIsNoBenefitAndLeavesNoAwardOnTheCharge() {
        ComputedCharge charge = withBenefit(offering(offer("B", "0.00")), "100.00", false);

        assertThat(charge.amounts().discount()).isEqualTo(hnl("0.00"));
        assertThat(charge.benefit()).isNull();
        assertThat(charge.netBase()).isEqualTo(hnl("100.00"));
    }

    @Test
    void withoutOffersTheChargeIsWhatItWasBeforeScholarships() {
        ComputedCharge charge = withBenefit(ChargeEvaluator.withoutBenefits(), "1100.00", true);

        assertThat(charge.amounts().discount()).isEqualTo(hnl("0.00"));
        assertThat(charge.benefit()).isNull();
        assertThat(charge.amounts().tax()).isEqualTo(hnl("165.00"));
        assertThat(charge.amounts().total()).isEqualTo(hnl("1265.00"));
    }

    @Test
    void ofTwoOffersTheGreatestBenefitIsAppliedAndOnlyOnce() {
        BenefitOffer small = offer("A-SMALL", "100.00");
        BenefitOffer great = offer("Z-GREAT", "300.00");

        ComputedCharge charge = withBenefit(offering(small, great), "1000.00", false);

        assertThat(charge.amounts().discount()).isEqualTo(hnl("300.00"));
        assertThat(charge.benefit()).isEqualTo(great);
        assertThat(charge.amounts().total()).isEqualTo(hnl("700.00"));
    }

    @Test
    void anOfferInAnotherCurrencyThanTheChargeIsNotApplied() {
        BenefitOffer usd = new BenefitOffer(UUID.randomUUID(), "USD-B", Money.of("10.00", CurrencyCode.USD));

        ComputedCharge charge = withBenefit(offering(usd), "100.00", false);

        assertThat(charge.amounts().discount()).isEqualTo(hnl("0.00"));
        assertThat(charge.benefit()).isNull();
    }

    @Provide
    Arbitrary<Long> baseCents() {
        return Arbitraries.longs().between(1, 99_999_999L);
    }

    @Provide
    Arbitrary<Long> rateBasisPoints() {
        // From 0.0001 % to 100 %, four decimals: the rates of the catalog.
        return Arbitraries.longs().between(1, 1_000_000L);
    }

    @Property
    void theTaxIsTheExactShareRoundedOnceHalfUpAndTheTotalIsTheExactSum(@ForAll("baseCents") long cents,
            @ForAll("rateBasisPoints") long ratePoints) {
        Money base = Money.of(BigDecimal.valueOf(cents, 2), CurrencyCode.HNL);
        Percentage percent = Percentage.of(BigDecimal.valueOf(ratePoints, 4));
        TaxRateInfo rate = new TaxRateInfo(UUID.randomUUID(), true, List.of(new TaxRateInfo.Validity(
                UUID.randomUUID(), percent, LocalDate.parse("2027-01-01"), null)));
        List<PriceCandidate> prices = List.of(new PriceCandidate(UUID.randomUUID(), PriceScope.INSTITUTION, null, base,
                LocalDate.parse("2027-01-01"), null));

        ComputedCharge charge = evaluate(student(true), concept(true, true, rate.taxRateId()), prices, rate);

        BigDecimal expected = base.amount().multiply(percent.value()).divide(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(charge.amounts().tax().amount().compareTo(expected)).isZero();
        assertThat(charge.amounts().total().equals(base.add(charge.amounts().tax()))).isTrue();
    }

    @Test
    void chargeAmountsRefuseAnInconsistentTotalANegativeAmountAndADiscountAboveTheBase() {
        Money base = Money.of("100.00", CurrencyCode.HNL);
        Money zero = Money.zero(CurrencyCode.HNL);
        assertThatThrownBy(() -> new ChargeAmounts(base, zero, zero, Money.of("100.01", CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(base, zero, Money.of("-1.00", CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(base, Money.of("100.01", CurrencyCode.HNL), zero))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(zero, zero, zero)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(Money.of("100.005", CurrencyCode.HNL), zero, zero))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(base, zero, Money.of("1.00", CurrencyCode.USD)))
                .isInstanceOf(com.confia.kernel.CurrencyMismatchException.class);
    }
}
