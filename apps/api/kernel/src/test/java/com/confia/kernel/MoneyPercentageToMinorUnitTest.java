package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * {@code percentageToMinorUnit}: the share of an amount rounded ONCE to the minor unit of the currency. The two-step
 * {@code percentage(..).roundToMinorUnit(..)} rounds to scale four first and then to two, so a share of 0.004995 became
 * 0.0050 and then 0.01 instead of 0.00 (docs/cortes/charges-accrual.md, appendix B).
 */
class MoneyPercentageToMinorUnitTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    @Test
    void roundsOnceWhereTheTwoStepRoundingRoundsTwice() {
        Money base = hnl("1.00");
        Percentage rate = Percentage.of("0.4995");

        assertThat(base.percentageToMinorUnit(rate, RoundingMode.HALF_UP)).isEqualTo(hnl("0.00"));
        // The documented defect of the composition: 0.004995 -> 0.0050 -> 0.01.
        assertThat(base.percentage(rate, RoundingMode.HALF_UP).roundToMinorUnit(RoundingMode.HALF_UP))
                .isEqualTo(hnl("0.01"));
    }

    @Test
    void theMidpointRoundsUp() {
        assertThat(hnl("0.05").percentageToMinorUnit(Percentage.of("10"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("0.01"));
        assertThat(hnl("0.50").percentageToMinorUnit(Percentage.of("1"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("0.01"));
        assertThat(hnl("0.49").percentageToMinorUnit(Percentage.of("1"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("0.00"));
    }

    @Test
    void anExactShareIsUnchanged() {
        assertThat(hnl("1500.00").percentageToMinorUnit(Percentage.of("10"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("150.00"));
        assertThat(hnl("1500.00").percentageToMinorUnit(Percentage.of("15"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("225.00"));
    }

    @Test
    void zeroAmountAndZeroRateGiveZero() {
        assertThat(hnl("0.00").percentageToMinorUnit(Percentage.of("15"), RoundingMode.HALF_UP).isZero()).isTrue();
        assertThat(hnl("99.99").percentageToMinorUnit(Percentage.of("0"), RoundingMode.HALF_UP).isZero()).isTrue();
    }

    @Test
    void aNegativeAmountRoundsTheMidpointAwayFromZero() {
        assertThat(hnl("-0.05").percentageToMinorUnit(Percentage.of("10"), RoundingMode.HALF_UP))
                .isEqualTo(hnl("-0.01"));
        assertThat(hnl("-1.00").percentageToMinorUnit(Percentage.of("0.4995"), RoundingMode.HALF_UP).isZero())
                .isTrue();
    }

    @Test
    void theResultIsAtScaleFourInTheSameCurrency() {
        Money result = Money.of("10.00", CurrencyCode.USD).percentageToMinorUnit(Percentage.of("15"),
                RoundingMode.HALF_UP);

        assertThat(result.amount().scale()).isEqualTo(Money.SCALE);
        assertThat(result.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(result).isEqualTo(Money.of("1.50", CurrencyCode.USD));
    }

    @Test
    void usesTheRoundingModeItIsGiven() {
        assertThat(hnl("0.05").percentageToMinorUnit(Percentage.of("10"), RoundingMode.DOWN)).isEqualTo(hnl("0.00"));
        assertThat(hnl("0.05").percentageToMinorUnit(Percentage.of("10"), RoundingMode.UP)).isEqualTo(hnl("0.01"));
    }

    @Test
    void refusesNullArguments() {
        Money amount = hnl("1.00");
        assertThatThrownBy(() -> amount.percentageToMinorUnit(null, RoundingMode.HALF_UP))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> amount.percentageToMinorUnit(Percentage.of("5"), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theLargestAmountAtOneHundredPercentStaysInRange() {
        Money largest = hnl("9999999999.99");
        assertThat(largest.percentageToMinorUnit(Percentage.of("100"), RoundingMode.HALF_UP)).isEqualTo(largest);
    }
}
