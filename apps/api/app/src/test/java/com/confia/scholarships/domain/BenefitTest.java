package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The value of a benefit and what it gives on a charge (docs/cortes/scholarships-awards.md, "Cálculo del beneficio"). */
class BenefitTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static void assertSame(Money actual, String expected) {
        assertThat(actual.compareTo(hnl(expected))).as("expected %s but was %s", expected, actual).isZero();
    }

    // --- Percentage bounds ---

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "25", "25.5", "99.99", "100", "100.00"})
    void aPercentageGreaterThanZeroAndUpToOneHundredWithTwoDecimalsIsAccepted(String points) {
        assertThat(Benefit.percentage(points).type()).isEqualTo(BenefitType.PERCENTAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "100.01", "101", "25.005", "25.001", "abc", "", "1e2", " 5"})
    void aPercentageOutOfItsBoundsOrWithMoreThanTwoDecimalsIsRefused(String points) {
        assertThatThrownBy(() -> Benefit.percentage(points)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("percent");
    }

    // --- Fixed amount bounds ---

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "500.00", "99999.99"})
    void aFixedAmountGreaterThanZeroWithTwoDecimalsIsAccepted(String amount) {
        assertThat(Benefit.fixed(amount).fixedAmount().currency()).isEqualTo(CurrencyCode.HNL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "10.005", "10.001", "x", ""})
    void aFixedAmountThatIsNotPositiveOrHasSubCentDigitsIsRefused(String amount) {
        assertThatThrownBy(() -> Benefit.fixed(amount)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("fixedAmount");
    }

    @Test
    void theCapIsOptionalAndWhenPresentIsPositiveWithTwoDecimals() {
        assertThat(Benefit.cap(null)).isNull();
        assertSame(Benefit.cap("300.00"), "300.00");
        assertThatThrownBy(() -> Benefit.cap("0")).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> Benefit.cap("-1")).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> Benefit.cap("1.005")).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aBenefitHasExactlyTheValueOfItsType() {
        assertThatThrownBy(() -> new Benefit(BenefitType.PERCENTAGE, null, null))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new Benefit(BenefitType.FIXED_AMOUNT, null, null))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new Benefit(BenefitType.FIXED_AMOUNT, Benefit.percentage("5").percent(), hnl("5")))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- The benefit on a charge ---

    @Test
    void aPercentageOfTheBaseIsTheShare() {
        assertSame(Benefit.percentage("25").on(hnl("1100.00"), null), "275.00");
    }

    @Test
    void aHundredPercentTakesTheWholeBase() {
        assertSame(Benefit.percentage("100").on(hnl("1100.00"), null), "1100.00");
    }

    @Test
    void aFractionIsRoundedHalfUpToTheMinorUnitOnce() {
        // 33.33 % of 100.05 = 33.346665 -> 33.35 ; 12.5 % of 0.04 = 0.005 -> 0.01 (HALF_UP, never HALF_EVEN).
        assertSame(Benefit.percentage("33.33").on(hnl("100.05"), null), "33.35");
        assertSame(Benefit.percentage("12.5").on(hnl("0.04"), null), "0.01");
        // 12.5 % of 0.03 = 0.00375 -> 0.00.
        assertSame(Benefit.percentage("12.5").on(hnl("0.03"), null), "0.00");
        // A share of 0.004995 (0.01 % of 49.95) rounds once to 0.00: rounding twice would give 0.01.
        assertSame(Benefit.percentage("0.01").on(hnl("49.95"), null), "0.00");
    }

    @Test
    void aFixedAmountIsTheAmount() {
        assertSame(Benefit.fixed("300.00").on(hnl("1100.00"), null), "300.00");
    }

    @Test
    void aFixedAmountGreaterThanTheBaseLeavesTheBaseAtZero() {
        assertSame(Benefit.fixed("2000.00").on(hnl("1100.00"), null), "1100.00");
    }

    @Test
    void theCapLimitsTheBenefitWhenItIsLessThanTheBenefit() {
        assertSame(Benefit.percentage("50").on(hnl("1100.00"), hnl("400.00")), "400.00");
        assertSame(Benefit.fixed("900.00").on(hnl("1100.00"), hnl("400.00")), "400.00");
    }

    @Test
    void theCapDoesNotRaiseABenefitThatIsLessThanIt() {
        assertSame(Benefit.percentage("10").on(hnl("1100.00"), hnl("400.00")), "110.00");
    }

    @Test
    void aCapGreaterThanTheBaseStillLeavesTheBaseAtZero() {
        assertSame(Benefit.fixed("5000.00").on(hnl("1100.00"), hnl("4000.00")), "1100.00");
    }

    @Test
    void aBaseThatIsZeroOrNegativeGetsNothing() {
        assertThat(Benefit.percentage("25").on(hnl("0.00"), null).isZero()).isTrue();
        assertThat(Benefit.fixed("25.00").on(hnl("-10.00"), null).isZero()).isTrue();
    }

    @Test
    void theBenefitInAnotherCurrencyThanHnlIsRefusedByTheKernel() {
        Money dollars = Money.of("100.00", CurrencyCode.USD);
        assertThatThrownBy(() -> Benefit.fixed("5.00").on(dollars, null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void twoBenefitsAreTheSameWhenTheirTypeAndValueAre() {
        assertThat(Benefit.percentage("25").sameAs(Benefit.percentage("25.00"))).isTrue();
        assertThat(Benefit.percentage("25").sameAs(Benefit.percentage("26"))).isFalse();
        assertThat(Benefit.fixed("25.00").sameAs(Benefit.fixed("25"))).isTrue();
        assertThat(Benefit.fixed("25.00").sameAs(Benefit.percentage("25"))).isFalse();
    }

    @Test
    void theAcceptanceExampleOfTheCut() {
        // A 25 % scholarship on a base of L 1,100.00 gives L 275.00: net base 825.00 (S2, the tax is the accrual's).
        Money benefit = Benefit.percentage("25").on(hnl("1100.00"), null);
        assertSame(benefit, "275.00");
        assertSame(hnl("1100.00").subtract(benefit), "825.00");
        assertThat(Stream.of(benefit).count()).isEqualTo(1);
    }
}
