package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Total ordered comparison between {@link Money} amounts of the same currency
 * (specs/money/spec.md, requirement "Comparación total ordenada entre importes de la misma
 * moneda").
 */
class MoneyComparisonTest {

    @Test
    void isGreaterThanIsTrueAndItsSymmetricIsLessThanIsAlsoTrue() {
        Money hundred = Money.of("100.00", CurrencyCode.HNL);
        Money fifty = Money.of("50.00", CurrencyCode.HNL);

        assertThat(hundred.isGreaterThan(fifty)).isTrue();
        assertThat(fifty.isLessThan(hundred)).isTrue();
    }

    @Test
    void isZeroIsPositiveAndIsNegativeAreExhaustiveAndMutuallyExclusive() {
        Money zero = Money.zero(CurrencyCode.HNL);
        Money positive = Money.of("10.00", CurrencyCode.HNL);
        Money negative = Money.of("-10.00", CurrencyCode.HNL);

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isPositive()).isFalse();
        assertThat(zero.isNegative()).isFalse();

        assertThat(positive.isZero()).isFalse();
        assertThat(positive.isPositive()).isTrue();
        assertThat(positive.isNegative()).isFalse();

        assertThat(negative.isZero()).isFalse();
        assertThat(negative.isPositive()).isFalse();
        assertThat(negative.isNegative()).isTrue();
    }

    @Test
    void compareToThrowsOnDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isGreaterThanThrowsOnDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.isGreaterThan(usd))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isGreaterThanOrEqualThrowsOnDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.isGreaterThanOrEqual(usd))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isLessThanThrowsOnDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.isLessThan(usd)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isLessThanOrEqualThrowsOnDifferentCurrencies() {
        Money hnl = Money.of("100.00", CurrencyCode.HNL);
        Money usd = Money.of("100.00", CurrencyCode.USD);

        assertThatThrownBy(() -> hnl.isLessThanOrEqual(usd))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isGreaterThanOrEqualIsTrueForEqualAmounts() {
        Money a = Money.of("100.00", CurrencyCode.HNL);
        Money b = Money.of("100.00", CurrencyCode.HNL);

        assertThat(a.isGreaterThanOrEqual(b)).isTrue();
        assertThat(a.isLessThanOrEqual(b)).isTrue();
    }

    @Test
    void compareToOrdersByAmountWithinTheSameCurrency() {
        Money fifty = Money.of("50.00", CurrencyCode.HNL);
        Money hundred = Money.of("100.00", CurrencyCode.HNL);

        assertThat(fifty.compareTo(hundred)).isNegative();
        assertThat(hundred.compareTo(fifty)).isPositive();
        assertThat(fifty.compareTo(Money.of("50.00", CurrencyCode.HNL))).isZero();
    }
}
