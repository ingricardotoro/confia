package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link PlainTotpSecret} (column-encryption-and-mfa-totp design.md decision 9; CLAUDE.md, regla
 * 11). Written because the split of cut C2 revealed this class had <b>no test at all</b>: 13 of its
 * 13 lines uncovered, exercised only incidentally by the integration tests of the next cut. It is
 * the one class of this change that holds a secret in clear text, so "covered by something else,
 * somewhere else" is not good enough for it.
 */
class PlainTotpSecretTest {

    private static final byte[] TWENTY_BYTES =
            HexFormat.of().parseHex("3132333435363738393031323334353637383930");

    @Test
    void generateProducesExactlyTwentyBytes() {
        PlainTotpSecret secret = PlainTotpSecret.generate(new SecureRandom());

        assertThat(secret.value()).hasSize(PlainTotpSecret.LENGTH_BYTES);
    }

    @Test
    void ofRejectsAnyLengthOtherThanTwenty() {
        assertThatThrownBy(() -> PlainTotpSecret.of(new byte[19]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 20 bytes");
        assertThatThrownBy(() -> PlainTotpSecret.of(new byte[21]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainTotpSecret.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Both directions of the defensive copy: mutating what the caller passed in, and mutating what
     * {@code value()} handed back, must both leave the secret this instance holds untouched. A
     * single shared array would make the secret editable from outside by either route.
     */
    @Test
    void neitherTheSourceArrayNorTheReturnedOneCanMutateTheSecret() {
        byte[] source = TWENTY_BYTES.clone();
        PlainTotpSecret secret = PlainTotpSecret.of(source);

        source[0] = (byte) 0xFF;
        assertThat(secret.value()).isEqualTo(TWENTY_BYTES);

        byte[] handedBack = secret.value();
        handedBack[0] = (byte) 0xFF;
        assertThat(secret.value()).isEqualTo(TWENTY_BYTES);
    }

    /**
     * The redaction, collected as text rather than merely constructed. Part 1's blocking finding was
     * a redaction test that built the leaking object and never read its {@code toString()}, so this
     * one asserts on the string itself and, separately, that no rendering of the secret's bytes
     * survives in it.
     */
    @Test
    void toStringIsRedactedAndCarriesNoRenderingOfTheSecret() {
        PlainTotpSecret secret = PlainTotpSecret.of(TWENTY_BYTES);

        String rendered = secret.toString();

        assertThat(rendered).isEqualTo("PlainTotpSecret[REDACTED]");
        assertThat(rendered)
                .as("neither the hex nor the ASCII rendering of the secret may survive in the text "
                        + "this object prints into a log")
                .doesNotContain(HexFormat.of().formatHex(TWENTY_BYTES))
                .doesNotContain("12345678901234567890");
    }
}
