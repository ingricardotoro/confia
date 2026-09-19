package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Permanent regression cases of ADR-0004 (specs/money/spec.md, requirement on the permanent
 * regression cases). These cases document the floating-point failures this project exists to
 * prevent. Do not delete or weaken any of them: a change that breaks one is a money defect, not an
 * outdated test.
 *
 * <p>This class holds the cases that need only {@code add}. The cases that need {@code multiply}
 * and {@code percentage} (the 15 % tax on 6.70 and the tuition times three) join it in PR 2
 * together with those operations.
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
}
