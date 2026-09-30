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

    /**
     * password-recovery-token design.md decision 13: a malformed code presented next to a reset
     * token must not end up in an exception message, so the message states the rule only.
     */
    @Test
    void theRejectionMessageNeverCarriesThePresentedValue() {
        assertThatThrownBy(() -> new TotpCode("12a456"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a TOTP code must be exactly 6 digits")
                .hasMessageNotContaining("12a456");
    }

    /** Decision 13: the record's generated {@code toString()} would print the six digits. */
    @Test
    void toStringNeverCarriesTheCode() {
        TotpCode code = new TotpCode("005924");

        org.assertj.core.api.Assertions.assertThat(code.toString())
                .doesNotContain("005924")
                .isEqualTo("TotpCode[REDACTED]");
    }

    @Test
    void acceptsAWellFormedSixDigitCode() {
        TotpCode code = new TotpCode("005924");

        org.assertj.core.api.Assertions.assertThat(code.value()).isEqualTo("005924");
    }
}
