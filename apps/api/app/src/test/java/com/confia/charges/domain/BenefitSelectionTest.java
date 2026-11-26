package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which of the benefits offered to one charge is applied: the greatest, and on a tie the scholarship with the lowest code. */
class BenefitSelectionTest {

    private static BenefitOffer offer(String code, String amount) {
        return new BenefitOffer(UUID.randomUUID(), code, Money.of(amount, CurrencyCode.HNL));
    }

    @Test
    void withoutOffersNothingIsApplied() {
        assertThat(BenefitSelection.best(List.of(), CurrencyCode.HNL)).isEmpty();
    }

    @Test
    void theGreatestBenefitWinsWhateverTheOrder() {
        BenefitOffer small = offer("A", "100.00");
        BenefitOffer great = offer("B", "275.00");
        BenefitOffer middle = offer("C", "150.00");

        assertThat(BenefitSelection.best(List.of(small, great, middle), CurrencyCode.HNL)).contains(great);
        assertThat(BenefitSelection.best(List.of(great, middle, small), CurrencyCode.HNL)).contains(great);
    }

    @Test
    void onATieTheScholarshipWithTheLowestCodeWins() {
        BenefitOffer first = offer("BECA-A", "200.00");
        BenefitOffer second = offer("BECA-B", "200.00");
        BenefitOffer lower = offer("BECA-0", "199.99");

        assertThat(BenefitSelection.best(List.of(second, first, lower), CurrencyCode.HNL)).contains(first);
        assertThat(BenefitSelection.best(List.of(first, second), CurrencyCode.HNL)).contains(first);
    }

    @Test
    void aTieOfTheSameScholarshipIsBrokenByTheAwardSoTheChoiceIsStable() {
        BenefitOffer one = new BenefitOffer(new UUID(0, 1), "SAME", Money.of("50.00", CurrencyCode.HNL));
        BenefitOffer two = new BenefitOffer(new UUID(0, 2), "SAME", Money.of("50.00", CurrencyCode.HNL));

        assertThat(BenefitSelection.best(List.of(two, one), CurrencyCode.HNL)).contains(one);
    }

    @Test
    void anOfferOfZeroIsNeverApplied() {
        assertThat(BenefitSelection.best(List.of(offer("A", "0.00")), CurrencyCode.HNL)).isEmpty();
        assertThat(BenefitSelection.best(List.of(offer("A", "0.00"), offer("B", "0.01")), CurrencyCode.HNL))
                .map(BenefitOffer::scholarshipCode).contains("B");
    }

    @Test
    void anOfferInAnotherCurrencyThanTheChargeIsIgnored() {
        BenefitOffer usd = new BenefitOffer(UUID.randomUUID(), "USD", Money.of("999.00", CurrencyCode.USD));
        BenefitOffer hnl = offer("HNL", "1.00");

        assertThat(BenefitSelection.best(List.of(usd, hnl), CurrencyCode.HNL)).contains(hnl);
        assertThat(BenefitSelection.best(List.of(usd), CurrencyCode.HNL)).isEmpty();
    }

    @Test
    void anOfferNeedsItsAwardItsCodeAndANonNegativeAmount() {
        assertThatThrownBy(() -> new BenefitOffer(null, "A", Money.of("1.00", CurrencyCode.HNL)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BenefitOffer(UUID.randomUUID(), " ", Money.of("1.00", CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new BenefitOffer(UUID.randomUUID(), "A", Money.of("-0.01", CurrencyCode.HNL)))
                .isInstanceOf(InvalidChargesValueException.class);
    }
}
