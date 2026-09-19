package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * {@code add}, {@code subtract}, {@code negate} and {@code multiply} on {@link Money}
 * (specs/money/spec.md, requirements "Suma y resta solo entre la misma moneda", "Importes
 * negativos como información contable" and "Redondeo explícito con `HALF_UP` en toda operación
 * que redondea"). Percentage and rounding to the minor unit are covered by their own test classes.
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
    void addingOrSubtractingZeroLeavesTheAmountUnchanged() {
        Money amount = Money.of("10.00", CurrencyCode.HNL);
        Money zero = Money.zero(CurrencyCode.HNL);

        assertThat(amount.add(zero)).isEqualTo(amount);
        assertThat(amount.subtract(zero)).isEqualTo(amount);
        assertThat(zero.subtract(amount)).isEqualTo(amount.negate());
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

    @Test
    void multipliesByAnExactIntegerFactorWithoutRounding() {
        Money threeMonthsOfTuition = Money.of("1234.55", CurrencyCode.HNL).multiply(3);

        assertThat(threeMonthsOfTuition).isEqualTo(Money.of("3703.65", CurrencyCode.HNL));
    }

    @Test
    void multiplyingByLongThatExceedsTheUpperLimitFailsWithoutConstructingAnyMoney() {
        Money nearTheLimit = Money.of("5000000000.0000", CurrencyCode.HNL);

        assertThatThrownBy(() -> nearTheLimit.multiply(3))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void multipliesByADecimalFactorWithASingleExplicitRounding() {
        Money one = Money.of("1.00", CurrencyCode.HNL);

        // Exact product is 0.333350; a single HALF_UP rounding to scale four gives 0.3334.
        Money result = one.multiply(new BigDecimal("0.33335"), RoundingMode.HALF_UP);

        assertThat(result).isEqualTo(Money.of("0.3334", CurrencyCode.HNL));
    }

    @Test
    void multiplyingByADecimalFactorNeedingNoRoundingIsExact() {
        Money base = Money.of("12.30", CurrencyCode.HNL);

        Money result = base.multiply(new BigDecimal("2"), RoundingMode.HALF_UP);

        assertThat(result).isEqualTo(Money.of("24.60", CurrencyCode.HNL));
    }

    @Test
    void multiplyingByADecimalFactorThatExceedsTheUpperLimitFailsAfterRounding() {
        Money nearTheLimit = Money.of("9999999999.9999", CurrencyCode.HNL);

        assertThatThrownBy(
                () -> nearTheLimit.multiply(new BigDecimal("1.5"), RoundingMode.HALF_UP))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }
}
