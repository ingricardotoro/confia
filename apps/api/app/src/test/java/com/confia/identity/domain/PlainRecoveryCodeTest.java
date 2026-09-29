package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/**
 * {@link PlainRecoveryCode} (column-encryption-and-mfa-totp design.md decision 9; CLAUDE.md, regla
 * 11). Written as its own test from the start, never left to be exercised only incidentally by an
 * integration test of a later task — the exact gap {@code PlainTotpSecretTest}'s own Javadoc
 * reports cut C2 found for that sibling class, after the fact. This class carries a
 * {@code String}, not a {@code byte[]}, so a bare {@code record} here would leak the code for real
 * — the redaction assertion below proves the class this phase actually wrote does not.
 */
class PlainRecoveryCodeTest {

    private static final String TEN_CHARACTERS = "A7K3M9QZC2";

    @Test
    void generateProducesExactlyTenCharacters() {
        PlainRecoveryCode code = PlainRecoveryCode.generate(new SecureRandom());

        assertThat(code.value()).hasSize(PlainRecoveryCode.LENGTH_CHARS);
    }

    @Test
    void generateProducesTwoDistinctCodesAcrossCalls() {
        SecureRandom random = new SecureRandom();
        PlainRecoveryCode first = PlainRecoveryCode.generate(random);
        PlainRecoveryCode second = PlainRecoveryCode.generate(random);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void ofRejectsAnyLengthOtherThanTen() {
        assertThatThrownBy(() -> PlainRecoveryCode.of("A7K3M9QZC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 10 characters");
        assertThatThrownBy(() -> PlainRecoveryCode.of("A7K3M9QZC22"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainRecoveryCode.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofAcceptsAValueOfExactlyTenCharacters() {
        PlainRecoveryCode code = PlainRecoveryCode.of(TEN_CHARACTERS);

        assertThat(code.value()).isEqualTo(TEN_CHARACTERS);
    }

    /**
     * The redaction, collected as text rather than merely constructed (specs/identity/spec.md,
     * "eso se verifica por inspección del texto producido, nunca por confianza en el diseño"). A
     * bare {@code record PlainRecoveryCode(String value)} would print {@code value} verbatim
     * through its generated {@code toString()} — this asserts the class actually written does not.
     */
    @Test
    void toStringIsRedactedAndCarriesNoRenderingOfTheCode() {
        PlainRecoveryCode code = PlainRecoveryCode.of(TEN_CHARACTERS);

        String rendered = code.toString();

        assertThat(rendered).isEqualTo("PlainRecoveryCode[REDACTED]");
        assertThat(rendered)
                .as("the clear-text recovery code must never survive in the text this object "
                        + "prints into a log")
                .doesNotContain(TEN_CHARACTERS);
    }
}
