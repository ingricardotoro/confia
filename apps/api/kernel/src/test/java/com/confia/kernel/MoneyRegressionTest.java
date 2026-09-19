package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Permanent regression cases of ADR-0004 (specs/money/spec.md, requirement on the permanent
 * regression cases). These cases document the floating-point failures this project exists to
 * prevent. Do not delete or weaken any of them: a change that breaks one is a money defect, not an
 * outdated test.
 *
 * <p>The two cases that need only {@code add} arrived in PR 1b. This task adds the two that need
 * {@code multiply} and {@code percentage}: the tax composes {@code percentage()} (which rounds
 * only to scale four by design, design.md decision 6) with {@code roundToMinorUnit(HALF_UP)} to
 * reach the spec's final displayed value.
 */
class MoneyRegressionTest {

    @Test
    void zeroPointOnePlusZeroPointTwoIsExactlyZeroPointThree() {
        Money sum = Money.of("0.1", CurrencyCode.HNL).add(Money.of("0.2", CurrencyCode.HNL));

        assertThat(sum).isEqualTo(Money.of("0.3", CurrencyCode.HNL));
    }

    @Test
    void aThousandAdditionsOfZeroPointOneAreExactlyOneHundred() {
        Money tenCents = Money.of("0.1", CurrencyCode.HNL);
        Money total = Money.zero(CurrencyCode.HNL);
        for (int i = 0; i < 1000; i++) {
            total = total.add(tenCents);
        }

        assertThat(total).isEqualTo(Money.of("100.00", CurrencyCode.HNL));
        assertThat(total.toPlainString()).isEqualTo("100.0000");
    }

    @Test
    void theFifteenPercentTaxOnSixSeventyRoundsToOneZeroOneNeverOneZeroZero() {
        // Exact intermediate is 1.0050 (percentage() rounds only to scale four); a double would
        // compute 6.70 * 0.15 as 1.0049999999999999, which rounds down to 1.00 instead.
        Money tax = Money.of("6.70", CurrencyCode.HNL)
                .percentage(Percentage.of("15"), RoundingMode.HALF_UP)
                .roundToMinorUnit(RoundingMode.HALF_UP);

        assertThat(tax).isEqualTo(Money.of("1.01", CurrencyCode.HNL));
    }

    @Test
    void threeMonthsOfTuitionAtOneThousandTwoThirtyFourFiftyFiveIsExactlyThreeThousandSevenZeroThreeSixtyFive() {
        Money threeMonthsOfTuition = Money.of("1234.55", CurrencyCode.HNL).multiply(3);

        assertThat(threeMonthsOfTuition).isEqualTo(Money.of("3703.65", CurrencyCode.HNL));
    }
}
