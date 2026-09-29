package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link TotpCode}'s own constructor validation (column-encryption-and-mfa-totp design.md,
 * decision 7): exactly six digits, never more, never fewer, and never unpadded.
 */
class TotpCodeTest {

    @Test
    void rejectsAFourDigitCodeWithoutLeadingZeroPadding() {
        assertThatThrownBy(() -> new TotpCode("5924"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsACodeWithMoreThanSixDigits() {
        assertThatThrownBy(() -> new TotpCode("1234567"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonNumericValue() {
        assertThatThrownBy(() -> new TotpCode("abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsAWellFormedSixDigitCode() {
        TotpCode code = new TotpCode("005924");

        org.assertj.core.api.Assertions.assertThat(code.value()).isEqualTo("005924");
    }
}
