package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Construction of {@link Percentage}, {@code Money}'s explicit collaborator for rate operations
 * (specs/money/spec.md, requirement "`Percentage` como colaborador explícito de `Money`").
 */
class PercentageTest {

    @Test
    void constructsFromAValidDecimalStringNormalizedToScaleFour() {
        Percentage fivePercent = Percentage.of("5");

        assertThat(fivePercent.value()).isEqualByComparingTo(new BigDecimal("5.0000"));
        assertThat(fivePercent.value().scale()).isEqualTo(4);
    }

    @Test
    void constructsFromABigDecimalNormalizedToScaleFour() {
        Percentage percentage = Percentage.of(new BigDecimal("12.5"));

        assertThat(percentage.value()).isEqualByComparingTo(new BigDecimal("12.5000"));
    }

    @Test
    void constructsAtTheUpperBoundOfTheClosedRange() {
        Percentage oneHundred = Percentage.of("100");

        assertThat(oneHundred.value()).isEqualByComparingTo(new BigDecimal("100.0000"));
    }

    @Test
    void constructsAtTheLowerBoundOfTheClosedRange() {
        Percentage zero = Percentage.of("0");

        assertThat(zero.value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsAValueAboveOneHundred() {
        assertThatThrownBy(() -> Percentage.of("100.01"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.OUT_OF_RANGE);
    }

    @Test
    void rejectsANegativeValue() {
        assertThatThrownBy(() -> Percentage.of("-0.01"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.OUT_OF_RANGE);
    }

    @Test
    void rejectsMoreThanFourDecimalDigitsTheSameWayMoneyDoes() {
        assertThatThrownBy(() -> Percentage.of("5.00001"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.SCALE_EXCEEDED);
    }

    @Test
    void rejectsAMalformedStringTheSameWayMoneyDoes() {
        assertThatThrownBy(() -> Percentage.of("1,5"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.MALFORMED);
    }

    @Test
    void malformedTakesPrecedenceOverScaleExceeded() {
        assertThatThrownBy(() -> Percentage.of("1,5.00001"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.MALFORMED);
    }

    @Test
    void scaleExceededTakesPrecedenceOverOutOfRange() {
        assertThatThrownBy(() -> Percentage.of("150.00001"))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.SCALE_EXCEEDED);
    }

    @Test
    void noFactoryAcceptsADoubleOrAFloatParameter() {
        Method[] factories = Arrays.stream(Percentage.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName().equals("of"))
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
    void equalsAndHashCodeAreBasedOnTheNormalizedValue() {
        assertThat(Percentage.of("5")).isEqualTo(Percentage.of("5.0"));
        assertThat(Percentage.of("5").hashCode()).isEqualTo(Percentage.of("5.0").hashCode());
        assertThat(Percentage.of("5")).isNotEqualTo(Percentage.of("6"));
    }

    @Test
    void isEqualToItselfByReference() {
        Percentage percentage = Percentage.of("5");

        assertThat(percentage).isEqualTo(percentage);
    }

    @Test
    void isNeverEqualToAnObjectOfAnotherType() {
        Percentage percentage = Percentage.of("5");

        assertThat(percentage).isNotEqualTo("5.0000");
        assertThat(percentage).isNotEqualTo(null);
    }

    @Test
    void rejectsAStringLongerThanSixtyFourCharacters() {
        String tooLong = "1".repeat(65);

        assertThatThrownBy(() -> Percentage.of(tooLong))
                .isInstanceOf(InvalidPercentageException.class)
                .extracting(exception -> ((InvalidPercentageException) exception).code())
                .isEqualTo(InvalidPercentageException.MALFORMED);
    }

    @Test
    void acceptsAStringOfExactlySixtyFourCharacters() {
        String atTheLimit = "1." + "0".repeat(62);

        assertThat(atTheLimit).hasSize(64);
        assertThat(Percentage.of(atTheLimit).toPlainString()).isEqualTo("1.0000");
    }

    @Test
    void hashCodeIsBasedOnTheNormalizedValue() {
        Percentage percentage = Percentage.of("15");

        assertThat(percentage.hashCode()).isEqualTo(percentage.value().hashCode());
    }

    @Test
    void toPlainStringIsExact() {
        assertThat(Percentage.of("15").toPlainString()).isEqualTo("15.0000");
    }

    @Test
    void toStringAppendsThePercentSign() {
        assertThat(Percentage.of("15").toString()).isEqualTo("15.0000%");
    }
}
