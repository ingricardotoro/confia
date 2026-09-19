package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;

/**
 * {@link CurrencyCode} is the closed, enabled set of currencies (proposal, technical decision 1;
 * design.md, decision 3). Its jqwik property also serves as the engine smoke test for task 1.7:
 * Surefire running a jqwik {@code @Property} on Java 25 with JUnit Platform 6 proves the engine
 * itself works before {@code Money} is written.
 */
class CurrencyCodeTest {

    @Test
    void hnlHasTwoMinorUnitDigits() {
        assertThat(CurrencyCode.HNL.minorUnitDigits()).isEqualTo(2);
    }

    @Test
    void usdHasTwoMinorUnitDigits() {
        assertThat(CurrencyCode.USD.minorUnitDigits()).isEqualTo(2);
    }

    @Test
    void fromIsoCodeResolvesAnEnabledUppercaseCode() {
        assertThat(CurrencyCode.fromIsoCode("HNL")).isEqualTo(CurrencyCode.HNL);
        assertThat(CurrencyCode.fromIsoCode("USD")).isEqualTo(CurrencyCode.USD);
    }

    @Test
    void fromIsoCodeIsCaseSensitiveAndRejectsLowercase() {
        assertThatThrownBy(() -> CurrencyCode.fromIsoCode("hnl"))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void fromIsoCodeRejectsACodeOutsideTheClosedSet() {
        assertThatThrownBy(() -> CurrencyCode.fromIsoCode("EUR"))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void unsupportedCurrencyExceptionHasTheExpectedCode() {
        UnsupportedCurrencyException exception = new UnsupportedCurrencyException();

        assertThat(exception.code()).isEqualTo("currency-unsupported");
        assertThat(exception).isInstanceOf(DomainException.class);
    }

    @Test
    void unsupportedCurrencyExceptionNeverEchoesTheRejectedInput() {
        String secretSoundingInput = "TOP-SECRET-CODE-4242";

        UnsupportedCurrencyException exception;
        try {
            CurrencyCode.fromIsoCode(secretSoundingInput);
            throw new AssertionError("expected UnsupportedCurrencyException");
        } catch (UnsupportedCurrencyException caught) {
            exception = caught;
        }

        assertThat(exception.getMessage()).doesNotContain(secretSoundingInput);
    }

    @Property
    void minorUnitDigitsNeverContradictsTheJdksOwnCurrencyTable(
            @ForAll("enabledCurrencies") CurrencyCode currency) {
        int jdkDefaultFractionDigits =
                Currency.getInstance(currency.name()).getDefaultFractionDigits();

        assertThat(currency.minorUnitDigits()).isEqualTo(jdkDefaultFractionDigits);
    }

    @Provide
    Arbitrary<CurrencyCode> enabledCurrencies() {
        return Arbitraries.of(CurrencyCode.values());
    }
}
