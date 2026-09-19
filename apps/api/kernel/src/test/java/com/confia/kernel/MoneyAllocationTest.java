package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code allocate}, proportional distribution of a total without losing the remainder
 * (specs/money/spec.md, requirement "Reparto proporcional sin pérdida de residuo (`allocate`)";
 * design.md, decision 7).
 */
class MoneyAllocationTest {

    @Test
    void allocatesWithARemainderDistributedByTheLargestRemainderMethod() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 1, 1);

        assertThat(parts).containsExactly(
                Money.of("33.34", CurrencyCode.HNL),
                Money.of("33.33", CurrencyCode.HNL),
                Money.of("33.33", CurrencyCode.HNL));
        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add))
                .isEqualTo(total);
    }

    @Test
    void allocatesExactlyWhenThereIsNoRemainder() {
        Money total = Money.of("90.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 1, 1);

        assertThat(parts).containsExactly(
                Money.of("30.00", CurrencyCode.HNL),
                Money.of("30.00", CurrencyCode.HNL),
                Money.of("30.00", CurrencyCode.HNL));
    }

    @Test
    void allocatesProportionallyToUnevenWeights() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(2, 1, 1);

        assertThat(parts).containsExactly(
                Money.of("50.00", CurrencyCode.HNL),
                Money.of("25.00", CurrencyCode.HNL),
                Money.of("25.00", CurrencyCode.HNL));
    }

    @Test
    void aWeightOfZeroAlwaysReceivesExactlyZero() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 0, 1);

        assertThat(parts.get(1)).isEqualTo(Money.zero(CurrencyCode.HNL));
        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add))
                .isEqualTo(total);
    }

    @Test
    void allocatingANegativeTotalDistributesTheAbsoluteValueAndAppliesTheSign() {
        Money total = Money.of("-100.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 1, 1);

        assertThat(parts).containsExactly(
                Money.of("-33.34", CurrencyCode.HNL),
                Money.of("-33.33", CurrencyCode.HNL),
                Money.of("-33.33", CurrencyCode.HNL));
        assertThat(parts.stream().reduce(Money.zero(CurrencyCode.HNL), Money::add))
                .isEqualTo(total);
    }

    @Test
    void rejectsAnEmptyWeightList() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        assertThatThrownBy(() -> total.allocate()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativeWeight() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        assertThatThrownBy(() -> total.allocate(1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAllWeightsBeingZero() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        assertThatThrownBy(() -> total.allocate(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsATotalThatIsNotAnExactMultipleOfTheMinorUnit() {
        Money total = Money.of("100.001", CurrencyCode.HNL);

        assertThatThrownBy(() -> total.allocate(1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allocatingZeroReturnsAllZeroParts() {
        Money total = Money.zero(CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 1, 1);

        assertThat(parts).containsExactly(
                Money.zero(CurrencyCode.HNL), Money.zero(CurrencyCode.HNL),
                Money.zero(CurrencyCode.HNL));
    }

    @Test
    void theReturnedListIsUnmodifiable() {
        Money total = Money.of("100.00", CurrencyCode.HNL);

        List<Money> parts = total.allocate(1, 1, 1);

        assertThatThrownBy(() -> parts.add(Money.zero(CurrencyCode.HNL)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
