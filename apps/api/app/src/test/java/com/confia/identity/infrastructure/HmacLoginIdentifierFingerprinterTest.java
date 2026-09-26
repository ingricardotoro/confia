package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link HmacLoginIdentifierFingerprinter} (design.md, decision 6, "Subllave para la huella del
 * identificador con separación de dominio"): the fingerprint never uses the pepper directly, but a
 * subkey derived from it with its own domain-separation label, so reusing the pepper for two
 * unrelated primitives is never a temptation.
 */
class HmacLoginIdentifierFingerprinterTest {

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(repeat((byte) 0x2a, 32)));
    private static final Argon2Pepper OTHER_PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(repeat((byte) 0x5c, 32)));

    private final HmacLoginIdentifierFingerprinter fingerprinter =
            new HmacLoginIdentifierFingerprinter(PEPPER);

    @Test
    void producesA64CharacterHexFingerprint() {
        IdentifierFingerprint fingerprint =
                fingerprinter.fingerprintOf(LoginIdentifier.of("maria.lopez@colegio.edu.hn"));

        assertThat(fingerprint.value()).hasSize(64);
    }

    @Test
    void isDeterministicForTheSameIdentifierAndPepper() {
        LoginIdentifier identifier = LoginIdentifier.of("carlos.ramirez@colegio.edu.hn");

        IdentifierFingerprint first = fingerprinter.fingerprintOf(identifier);
        IdentifierFingerprint second = fingerprinter.fingerprintOf(identifier);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentIdentifiersProduceDifferentFingerprints() {
        IdentifierFingerprint first =
                fingerprinter.fingerprintOf(LoginIdentifier.of("maria.lopez@colegio.edu.hn"));
        IdentifierFingerprint second =
                fingerprinter.fingerprintOf(LoginIdentifier.of("nadie.registrado@colegio.edu.hn"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void differentPeppersProduceDifferentFingerprintsForTheSameIdentifier() {
        LoginIdentifier identifier = LoginIdentifier.of("maria.lopez@colegio.edu.hn");
        HmacLoginIdentifierFingerprinter withOtherPepper =
                new HmacLoginIdentifierFingerprinter(OTHER_PEPPER);

        IdentifierFingerprint first = fingerprinter.fingerprintOf(identifier);
        IdentifierFingerprint second = withOtherPepper.fingerprintOf(identifier);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsANullPepper() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new HmacLoginIdentifierFingerprinter(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullIdentifier() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fingerprinter.fingerprintOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static byte[] repeat(byte b, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, b);
        return bytes;
    }
}
