package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Construction and equality of {@link Money} (specs/money/spec.md, requirements "Construcción y
 * normalización a escala cuatro" and "Igualdad por importe normalizado y moneda").
 */
class MoneyConstructionTest {

    @Test
    void constructsFromAValidDecimalStringNormalizedToScaleFour() {
        Money money = Money.of("1250.15", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("1250.1500"));
        assertThat(money.amount().scale()).isEqualTo(4);
        assertThat(money.currency()).isEqualTo(CurrencyCode.HNL);
    }

    @Test
    void zeroConstructsToScaleFourZero() {
        Money zero = Money.zero(CurrencyCode.HNL);

        assertThat(zero.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(zero.toPlainString()).isEqualTo("0.0000");
    }

    @Test
    void constructsANegativeAmount() {
        Money money = Money.of("-45.50", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("-45.5000"));
    }

    @Test
    void constructsTheMinimumRepresentableAmount() {
        Money money = Money.of("0.01", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("0.0100"));
    }

    @Test
    void constructsAtTheNumeric14x4Limit() {
        Money money = Money.of("9999999999.9999", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("9999999999.9999"));
    }

    @Test
    void constructsAtTheNegativeNumeric14x4Limit() {
        Money money = Money.of("-9999999999.9999", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("-9999999999.9999"));
    }

    @Test
    void rejectsMoreThanFourDecimalDigits() {
        assertThatThrownBy(() -> Money.of("1.00001", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED);
    }

    @Test
    void acceptsTrailingZerosBeyondScaleFourBecauseNoInformationIsLost() {
        Money money = Money.of("1.000000", CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    @Test
    void rejectsAMalformedStringWithAThousandsSeparator() {
        assertThatThrownBy(() -> Money.of("1,234.56", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.MALFORMED);
    }

    @Test
    void rejectsExponentialNotation() {
        assertThatThrownBy(() -> Money.of("1.25E3", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.MALFORMED);
    }

    @Test
    void rejectsALeadingPlusSign() {
        assertThatThrownBy(() -> Money.of("+45.50", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.MALFORMED);
    }

    @Test
    void rejectsAboveTheNumeric14x4Limit() {
        assertThatThrownBy(() -> Money.of("10000000000.0000", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void malformedTakesPrecedenceOverScaleExceeded() {
        // Both malformed (thousands separator) and more than four decimals.
        assertThatThrownBy(() -> Money.of("1,234.567890", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.MALFORMED);
    }

    @Test
    void scaleExceededTakesPrecedenceOverOutOfRange() {
        // Both out of range (12 integer digits) and more than four decimals.
        assertThatThrownBy(() -> Money.of("999999999999.00001", CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED);
    }

    @Test
    void noFactoryAcceptsADoubleOrAFloatParameter() {
        Method[] factories = Arrays.stream(Money.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("of") || method.getName().equals("zero"))
                .toArray(Method[]::new);

        assertThat(factories).isNotEmpty();
        for (Method factory : factories) {
            assertThat(factory.getParameterTypes())
                    .as("factory %s must not accept double or float", factory)
                    .noneMatch(type -> type == double.class || type == float.class
                            || type == Double.class || type == Float.class);
        }
    }

    @Test
    void treatsDifferentInputScalesAsTheSameAmountWithTheSameHashCode() {
        Money oneDotZero = Money.of("1.0", CurrencyCode.HNL);
        Money oneDotZeroZero = Money.of("1.00", CurrencyCode.HNL);

        assertThat(oneDotZero).isEqualTo(oneDotZeroZero);
        assertThat(oneDotZero.hashCode()).isEqualTo(oneDotZeroZero.hashCode());
    }

    @Test
    void hashCodeCombinesTheNormalizedAmountAndTheCurrency() {
        Money money = Money.of("1250.15", CurrencyCode.USD);

        assertThat(money.hashCode())
                .isEqualTo(31 * money.amount().hashCode() + money.currency().hashCode());
        assertThat(money.hashCode()).isNotEqualTo(Money.of("1250.15", CurrencyCode.HNL).hashCode());
    }

    @Test
    void theSameAmountInDifferentCurrenciesIsNotEqual() {
        assertThat(Money.of("100.00", CurrencyCode.HNL))
                .isNotEqualTo(Money.of("100.00", CurrencyCode.USD));
    }

    @Test
    void differentAmountsInTheSameCurrencyAreNotEqual() {
        assertThat(Money.of("100.00", CurrencyCode.HNL))
                .isNotEqualTo(Money.of("100.01", CurrencyCode.HNL));
    }

    @Test
    void toPlainStringIsExactWithoutScientificNotation() {
        Money money = Money.of("1250.1500", CurrencyCode.HNL);

        assertThat(money.toPlainString()).isEqualTo("1250.1500");
    }

    @Test
    void aRoundTripThroughToPlainStringPreservesTheExactValue() {
        Money original = Money.of("1234.5678", CurrencyCode.HNL);

        Money reconstructed = Money.of(original.toPlainString(), original.currency());

        assertThat(reconstructed).isEqualTo(original);
    }

    @Test
    void constructsWithEachEnabledCurrency() {
        assertThat(Money.of("100.00", CurrencyCode.HNL).currency()).isEqualTo(CurrencyCode.HNL);
        assertThat(Money.of("100.00", CurrencyCode.USD).currency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void constructsFromABigDecimalNormalizedToScaleFour() {
        Money money = Money.of(new BigDecimal("100"), CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void theBigDecimalFactoryAcceptsAnyInputScaleIncludingNegative() {
        // scale -2: what a driver hands back for a value like 100 stored as 1E+2.
        Money money = Money.of(new BigDecimal(java.math.BigInteger.ONE, -2), CurrencyCode.HNL);

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void theBigDecimalFactoryStillRejectsMoreThanFourDecimalDigits() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1.00001"), CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED);
    }

    @Test
    void theBigDecimalFactoryStillRejectsAnOutOfRangeAmount() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10000000000.0000"), CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void acceptsAStringOfExactlySixtyFourCharacters() {
        String atTheLimit = "1." + "0".repeat(62);

        assertThat(atTheLimit).hasSize(64);
        assertThat(Money.of(atTheLimit, CurrencyCode.HNL).toPlainString()).isEqualTo("1.0000");
    }

    @Test
    void rejectsAStringLongerThanSixtyFourCharacters() {
        String tooLong = "1".repeat(65);

        assertThatThrownBy(() -> Money.of(tooLong, CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.MALFORMED);
    }

    @Test
    void rejectsAnExtremelyLargePositiveInputScaleWithoutHanging() {
        // Regression: an unbounded setScale on a BigDecimal with a huge scale costs roughly
        // 10^|scale| (measured: a scale of ten million takes about 1.4 seconds; five hundred
        // million never returns). The scale bound must reject this before any setScale runs.
        BigDecimal extremeScale = new BigDecimal(BigInteger.ONE, 500_000_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> Money.of(extremeScale, CurrencyCode.HNL))
                        .isInstanceOf(InvalidMoneyAmountException.class)
                        .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                        .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED));
    }

    @Test
    void rejectsAnExtremelyNegativeInputScaleWithoutHanging() {
        BigDecimal extremeScale = new BigDecimal(BigInteger.ONE, -500_000_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> Money.of(extremeScale, CurrencyCode.HNL))
                        .isInstanceOf(InvalidMoneyAmountException.class)
                        .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                        .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE));
    }

    @Test
    void treatsAZeroValueOfAnExtremeScaleAsZeroWithoutHanging() {
        // The zero short-circuit must run before any scale bound is even consulted, in either
        // direction, so a zero coming back from the database at an extreme scale never triggers
        // the denial-of-service protection instead of simply being zero.
        BigDecimal extremePositiveScaleZero = new BigDecimal(BigInteger.ZERO, 500_000_000);
        BigDecimal extremeNegativeScaleZero = new BigDecimal(BigInteger.ZERO, -500_000_000);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(Money.of(extremePositiveScaleZero, CurrencyCode.HNL))
                    .isEqualTo(Money.zero(CurrencyCode.HNL));
            assertThat(Money.of(extremeNegativeScaleZero, CurrencyCode.HNL))
                    .isEqualTo(Money.zero(CurrencyCode.HNL));
        });
    }

    @Test
    void acceptsABigDecimalInputScaleOfExactlyThirtyFourWhenAllTrailingDigitsAreZero() {
        BigDecimal atTheLimit = new BigDecimal("1").setScale(34);

        assertThat(Money.of(atTheLimit, CurrencyCode.HNL).toPlainString()).isEqualTo("1.0000");
    }

    @Test
    void rejectsABigDecimalInputScaleOfThirtyFiveEvenWithOnlyTrailingZeros() {
        // Deliberate trade-off (design.md, decision 4 addendum): the input scale bound rejects
        // this even though every digit beyond scale four is zero and no precision would be lost.
        BigDecimal oneMoreThanTheLimit = new BigDecimal("1").setScale(35);

        assertThatThrownBy(() -> Money.of(oneMoreThanTheLimit, CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED);
    }

    @Test
    void isEqualToItselfByReference() {
        Money money = Money.of("100.00", CurrencyCode.HNL);

        assertThat(money).isEqualTo(money);
    }

    @Test
    void isNeverEqualToAnObjectOfAnotherType() {
        Money money = Money.of("100.00", CurrencyCode.HNL);

        assertThat(money).isNotEqualTo("100.00");
        assertThat(money).isNotEqualTo(null);
    }

    @Test
    void toStringIsTheAmountFollowedBySpaceAndCurrencyForLogsOnly() {
        Money money = Money.of("1234.55", CurrencyCode.HNL);

        assertThat(money.toString()).isEqualTo("1234.5500 HNL");
    }

    @Test
    void theBigDecimalFactoryAcceptsANegativeScaleAtExactlyTenIntegerDigits() {
        // 1E+9 has precision 1 and scale -9: ten integer digits, the largest count in range.
        Money money = Money.of(new BigDecimal("1E+9"), CurrencyCode.HNL);

        assertThat(money.toPlainString()).isEqualTo("1000000000.0000");
    }

    @Test
    void theBigDecimalFactoryRejectsANegativeScaleBeyondTenIntegerDigitsAsOutOfRange() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("1E+10"), CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.OUT_OF_RANGE);
    }

    @Test
    void theBigDecimalFactoryKeepsScaleExceededAheadOfOutOfRange() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10000000000.00001"), CurrencyCode.HNL))
                .isInstanceOf(InvalidMoneyAmountException.class)
                .extracting(exception -> ((InvalidMoneyAmountException) exception).code())
                .isEqualTo(InvalidMoneyAmountException.SCALE_EXCEEDED);
    }
}
