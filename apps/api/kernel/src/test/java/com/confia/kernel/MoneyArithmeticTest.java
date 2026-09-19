package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@code add}, {@code subtract} and {@code negate} on {@link Money} (specs/money/spec.md,
 * requirements "Suma y resta solo entre la misma moneda" and "Importes negativos como información
 * contable"). Multiplication, percentage and rounding arrive in PR 2 (design.md, "Entrega en dos
 * pull requests").
 */
class MoneyArithmeticTest {

    @Test
    void addsTwoAmountsInTheSameCurrency() {
        Money a = Money.of("120.50", CurrencyCode.HNL);
        Money b = Money.of("30.25", CurrencyCode.HNL);

        assertThat(a.add(b)).isEqualTo(Money.of("150.75", CurrencyCode.HNL));
    }

    @Test
    void subtractsTwoAmountsInTheSameCurrency() {
        Money a = Money.of("120.50", CurrencyCode.HNL);
        Money b = Money.of("30.25", CurrencyCode.HNL);

        assertThat(a.subtract(b)).isEqualTo(Money.of("90.25", CurrencyCode.HNL));
    }

    @Test
    void addingDifferentCurrenciesThrowsAndConstructsNoMoney() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.add(usd))
                .isInstanceOf(CurrencyMismatchException.class)
                .satisfies(exception -> {
                    CurrencyMismatchException mismatch = (CurrencyMismatchException) exception;
                    assertThat(mismatch.expected()).isEqualTo(CurrencyCode.HNL);
                    assertThat(mismatch.actual()).isEqualTo(CurrencyCode.USD);
                });
    }

    @Test
    void subtractingDifferentCurrenciesThrows() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.subtract(usd)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void addingAboveTheUpperNumeric14x4LimitFailsWithoutConstructingAnyMoney() {
        Money atLimit = Money.of("9999999999.9999", CurrencyCode.HNL);
        Money oneMoreUnit = Money.of("0.0001", CurrencyCode.HNL);

        assertThatThrownBy(() -> atLimit.add(oneMoreUnit))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void subtractingBelowTheLowerNumeric14x4LimitFailsWithoutConstructingAnyMoney() {
        Money atLowerLimit = Money.of("-9999999999.9999", CurrencyCode.HNL);
        Money oneMoreUnit = Money.of("0.0001", CurrencyCode.HNL);

        assertThatThrownBy(() -> atLowerLimit.subtract(oneMoreUnit))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void addsANegativeAmountWithoutNormalizingItsSign() {
        Money negative = Money.of("-45.50", CurrencyCode.HNL);
        Money positive = Money.of("20.00", CurrencyCode.HNL);

        assertThat(negative.add(positive)).isEqualTo(Money.of("-25.50", CurrencyCode.HNL));
    }

    @Test
    void negateFlipsTheSign() {
        Money positive = Money.of("45.50", CurrencyCode.HNL);

        assertThat(positive.negate()).isEqualTo(Money.of("-45.50", CurrencyCode.HNL));
    }

    @Test
    void negatingZeroIsStillZero() {
        assertThat(Money.zero(CurrencyCode.HNL).negate()).isEqualTo(Money.zero(CurrencyCode.HNL));
    }

    @Test
    void negatingTwiceReturnsTheOriginalAmount() {
        Money original = Money.of("45.50", CurrencyCode.HNL);

        assertThat(original.negate().negate()).isEqualTo(original);
    }

    @Test
    void moneyOffersNoAbsoluteValueOperation() {
        boolean hasAbsoluteValueMethod = Arrays.stream(Money.class.getMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equalsIgnoreCase("abs")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("absolute"));

        assertThat(hasAbsoluteValueMethod)
                .as("Money must not offer an absolute-value operation; correcting an unexpected "
                        + "sign is the responsibility of the owning business module, never a side "
                        + "effect of Money (specs/money/spec.md)")
                .isFalse();
    }
}
