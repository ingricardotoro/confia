package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Base32} against the seven test vectors of RFC 4648, section 10 — the encoding every
 * authenticator app accepts as manual entry of a TOTP secret (specs/identity/spec.md, escenario "La
 * inscripción devuelve el secreto en base32 una única vez").
 *
 * <p>The vectors are transcribed from the RFC, not derived: each expected value below is the exact
 * string that section 10 prints for its input, padding included. {@link PlainTotpSecret} never hits
 * a padded case — 20 bytes is a multiple of 5, so its encoding is always 32 characters with no
 * {@code =} — but encoding only complete groups would leave the padding arithmetic untested, and an
 * untested branch is what a future caller with a different length would walk into.
 */
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
        "'',       ''",
        "f,        MY======",
        "fo,       MZXQ====",
        "foo,      MZXW6===",
        "foob,     MZXW6YQ=",
        "fooba,    MZXW6YTB",
        "foobar,   MZXW6YTBOI======",
    })
    void encodesTheRfc4648Vectors(String input, String expected) {
        assertThat(Base32.encode(input.getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(expected);
    }

    /**
     * The shape a TOTP secret actually takes: the RFC 6238 shared secret is 20 ASCII bytes, and
     * 160 bits divide exactly into 32 groups of 5, so the result carries no padding at all.
     */
    @Test
    void aTwentyByteSecretEncodesToThirtyTwoCharactersWithNoPadding() {
        byte[] rfc6238SharedSecret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        String encoded = Base32.encode(rfc6238SharedSecret);

        assertThat(encoded).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(encoded).hasSize(32).doesNotContain("=");
    }

    @Test
    void usesOnlyTheRfc4648Alphabet() {
        byte[] everyByteValue = new byte[256];
        for (int i = 0; i < everyByteValue.length; i++) {
            everyByteValue[i] = (byte) i;
        }

        assertThat(Base32.encode(everyByteValue))
                .as("an encoder that leaked a byte through unmapped would show up as a character "
                        + "outside the alphabet RFC 4648 fixes")
                .matches("[A-Z2-7]+=*");
    }
}
