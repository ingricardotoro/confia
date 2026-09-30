package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link PlainPasswordResetToken} (password-recovery-token design.md decision 3;
 * specs/identity/spec.md, "El token entregado tiene 32 bytes y no se repite"): 32 random bytes as 43
 * base64url characters without padding, never printed and never echoed in a rejection message.
 */
class PlainPasswordResetTokenTest {

    private static final String FORTY_THREE = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";

    @Test
    void generateProducesFortyThreeUnpaddedBase64UrlCharactersThatDecodeToThirtyTwoBytes() {
        SecureRandom seeded = seededRandom();

        PlainPasswordResetToken token = PlainPasswordResetToken.generate(seeded);

        assertThat(token.value()).hasSize(PlainPasswordResetToken.LENGTH_CHARS)
                .hasSize(43)
                .matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Base64.getUrlDecoder().decode(token.value()))
                .hasSize(PlainPasswordResetToken.RANDOM_BYTES)
                .hasSize(32);
    }

    @Test
    void generateDrawsItsBytesFromTheGivenRandom() {
        byte[] expected = new byte[32];
        seededRandom().nextBytes(expected);

        PlainPasswordResetToken token = PlainPasswordResetToken.generate(seededRandom());

        assertThat(Base64.getUrlDecoder().decode(token.value())).isEqualTo(expected);
    }

    @Test
    void twoGenerationsWithTheRealRandomDiffer() {
        SecureRandom random = new SecureRandom();

        assertThat(PlainPasswordResetToken.generate(random))
                .isNotEqualTo(PlainPasswordResetToken.generate(random));
    }

    @Test
    void ofAcceptsFortyThreeBase64UrlCharacters() {
        assertThat(PlainPasswordResetToken.of(FORTY_THREE).value()).isEqualTo(FORTY_THREE);
    }

    @Test
    void ofRejectsFortyTwoAndFortyFourCharacters() {
        assertThatThrownBy(() -> PlainPasswordResetToken.of(FORTY_THREE.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainPasswordResetToken.of(FORTY_THREE + "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainPasswordResetToken.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofRejectsCharactersOutsideBase64UrlWithoutEchoingTheValue() {
        String withPlusAndSlash = "q9Zb2X+kP3r/Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
        String withPadding = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQ=";

        assertThatThrownBy(() -> PlainPasswordResetToken.of(withPlusAndSlash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(withPlusAndSlash)
                .hasMessageNotContaining("q9Zb2X");
        assertThatThrownBy(() -> PlainPasswordResetToken.of(withPadding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(withPadding);
    }

    @Test
    void toStringIsRedacted() {
        PlainPasswordResetToken token = PlainPasswordResetToken.of(FORTY_THREE);

        assertThat(token.toString()).doesNotContain(FORTY_THREE).doesNotContain("q9Zb2X")
                .isEqualTo("PlainPasswordResetToken[REDACTED]");
    }

    @Test
    void equalityFollowsTheValue() {
        assertThat(PlainPasswordResetToken.of(FORTY_THREE))
                .isEqualTo(PlainPasswordResetToken.of(FORTY_THREE))
                .hasSameHashCodeAs(PlainPasswordResetToken.of(FORTY_THREE))
                .isNotEqualTo(PlainPasswordResetToken.of("A".repeat(43)))
                .isNotEqualTo(FORTY_THREE);
    }

    /** A deterministic random: SHA1PRNG seeded before first use never mixes in system entropy. */
    private static SecureRandom seededRandom() {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(20260930L);
            return random;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
