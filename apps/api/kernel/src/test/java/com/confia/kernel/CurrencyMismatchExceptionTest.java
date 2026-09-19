package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link CurrencyMismatchException}: thrown when an operation mixes two different currencies. */
class CurrencyMismatchExceptionTest {

    @Test
    void extendsDomainException() {
        CurrencyMismatchException exception =
                new CurrencyMismatchException(CurrencyCode.HNL, CurrencyCode.USD);

        assertThat(exception).isInstanceOf(DomainException.class);
    }

    @Test
    void hasTheCurrencyMismatchCode() {
        CurrencyMismatchException exception =
                new CurrencyMismatchException(CurrencyCode.HNL, CurrencyCode.USD);

        assertThat(exception.code()).isEqualTo("currency-mismatch");
        assertThat(CurrencyMismatchException.CODE).isEqualTo("currency-mismatch");
    }

    @Test
    void exposesTheExpectedAndActualCurrencies() {
        CurrencyMismatchException exception =
                new CurrencyMismatchException(CurrencyCode.HNL, CurrencyCode.USD);

        assertThat(exception.expected()).isEqualTo(CurrencyCode.HNL);
        assertThat(exception.actual()).isEqualTo(CurrencyCode.USD);
    }
}
