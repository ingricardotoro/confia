package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link Argon2Pepper} construction guards and redaction (design.md, decision 6: "falla al
 * construirse si falta o si mide otra cosa"; CLAUDE.md regla 11). How the Base64 value reaches
 * this constructor — environment variable, mounted secret, or a real secrets manager — is this
 * change's deliberately undecided wiring (design.md, decision 6: "aquí llega por constructor... el
 * cableado real... es del cambio 11"), so this test only exercises the value object itself, never
 * an environment variable.
 *
 * <p>The literal 32-byte value below is declared not secret in this very comment, following the
 * reviewed precedent of {@code create-test-roles.sql:8-10} (design.md, decision 6).
 */
class Argon2PepperTest {

    /** Not a secret: 32 literal bytes, declared as such, for tests only. */
    private static final String VALID_32_BYTE_PEPPER_BASE64 =
            Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void acceptsExactly32Bytes() {
        Argon2Pepper pepper = Argon2Pepper.fromBase64(VALID_32_BYTE_PEPPER_BASE64);

        assertThat(pepper.value()).hasSize(32);
    }

    @Test
    void rejectsANullValue() {
        assertThatThrownBy(() -> Argon2Pepper.fromBase64(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsABlankValue() {
        assertThatThrownBy(() -> Argon2Pepper.fromBase64("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidBase64() {
        assertThatThrownBy(() -> Argon2Pepper.fromBase64("not-valid-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAValueShorterThan32Bytes() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> Argon2Pepper.fromBase64(tooShort))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAValueLongerThan32Bytes() {
        String tooLong = Base64.getEncoder().encodeToString(new byte[33]);

        assertThatThrownBy(() -> Argon2Pepper.fromBase64(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueReturnsADefensiveCopyEachTime() {
        Argon2Pepper pepper = Argon2Pepper.fromBase64(VALID_32_BYTE_PEPPER_BASE64);

        byte[] first = pepper.value();
        first[0] = (byte) 0xFF;
        byte[] second = pepper.value();

        assertThat(second[0]).isNotEqualTo((byte) 0xFF);
    }

    @Test
    void toStringNeverContainsAnyTraceOfThePepper() {
        Argon2Pepper pepper = Argon2Pepper.fromBase64(VALID_32_BYTE_PEPPER_BASE64);

        assertThat(pepper.toString()).doesNotContain(VALID_32_BYTE_PEPPER_BASE64);
    }
}
