package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** A discount in force as the charges module receives it: what it gives is never more than the base nor in another currency. */
class DiscountCandidateTest {

    private static final Money BASE = Money.of("100.00", CurrencyCode.HNL);

    private static DiscountCandidate candidate(UnaryOperator<Money> benefit) {
        return new DiscountCandidate(UUID.randomUUID(), "CAMP-10", null, true, benefit);
    }

    @Test
    void aDiscountWithoutACodeIsRefused() {
        assertThatThrownBy(() -> new DiscountCandidate(UUID.randomUUID(), " ", null, true, base -> base))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void whatItGivesIsReturnedWhenItIsAnAmountOfTheBaseCurrencyThatDoesNotExceedTheBase() {
        assertThat(candidate(base -> Money.of("10.00", CurrencyCode.HNL)).on(BASE)).isEqualTo(Money.of("10.00", CurrencyCode.HNL));
        assertThat(candidate(base -> base).on(BASE)).as("the whole base is the most it can give").isEqualTo(BASE);
        assertThat(candidate(base -> Money.zero(CurrencyCode.HNL)).on(BASE).isZero()).isTrue();
    }

    @Test
    void aFunctionThatAnswersNothingAnotherCurrencyANegativeAmountOrMoreThanTheBaseIsADefect() {
        assertThatThrownBy(() -> candidate(base -> null).on(BASE)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> candidate(base -> Money.of("10.00", CurrencyCode.USD)).on(BASE))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> candidate(base -> Money.of("-1.00", CurrencyCode.HNL)).on(BASE))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> candidate(base -> Money.of("100.01", CurrencyCode.HNL)).on(BASE))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
