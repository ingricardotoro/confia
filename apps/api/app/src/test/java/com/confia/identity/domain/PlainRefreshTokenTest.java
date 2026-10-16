package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link PlainRefreshToken} (session-tokens-and-web-layer design.md decision 9; scenario I87): 32
 * random bytes as 43 base64url characters without padding, delivered once, never printed and never
 * echoed in a rejection message.
 */
class PlainRefreshTokenTest {

    private static final String FORTY_THREE = "Zk3vQ8-rT1mYw6_NcB9sHd2LpXe4GfJ7uAiO0aRtVyE";

    @Test
    void generateProducesFortyThreeUnpaddedBase64UrlCharactersThatDecodeToThirtyTwoBytes() {
        PlainRefreshToken token = PlainRefreshToken.generate(seededRandom());

        assertThat(token.value()).hasSize(PlainRefreshToken.LENGTH_CHARS).hasSize(43)
                .matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Base64.getUrlDecoder().decode(token.value()))
                .hasSize(PlainRefreshToken.RANDOM_BYTES).hasSize(32);
    }

    @Test
    void generateDrawsItsBytesFromTheGivenRandom() {
        byte[] expected = new byte[32];
        seededRandom().nextBytes(expected);

        PlainRefreshToken token = PlainRefreshToken.generate(seededRandom());

        assertThat(Base64.getUrlDecoder().decode(token.value())).isEqualTo(expected);
    }

    @Test
    void twoGenerationsWithTheRealRandomDiffer() {
        SecureRandom random = new SecureRandom();

        assertThat(PlainRefreshToken.generate(random)).isNotEqualTo(PlainRefreshToken.generate(random));
    }

    @Test
    void ofAcceptsFortyThreeBase64UrlCharactersAndNothingElse() {
        assertThat(PlainRefreshToken.of(FORTY_THREE).value()).isEqualTo(FORTY_THREE);
        assertThatThrownBy(() -> PlainRefreshToken.of(FORTY_THREE.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainRefreshToken.of(FORTY_THREE + "A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlainRefreshToken.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ofRejectsCharactersOutsideBase64UrlWithoutEchoingTheValue() {
        String withPlusAndSlash = "Zk3vQ8+rT1mYw6/NcB9sHd2LpXe4GfJ7uAiO0aRtVyE";
        String withPadding = "Zk3vQ8-rT1mYw6_NcB9sHd2LpXe4GfJ7uAiO0aRtVy=";

        assertThatThrownBy(() -> PlainRefreshToken.of(withPlusAndSlash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(withPlusAndSlash).hasMessageNotContaining("Zk3vQ8");
        assertThatThrownBy(() -> PlainRefreshToken.of(withPadding))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(withPadding);
    }

    @Test
    void toStringIsRedacted() {
        assertThat(PlainRefreshToken.of(FORTY_THREE).toString()).doesNotContain(FORTY_THREE)
                .doesNotContain("Zk3vQ8").isEqualTo("PlainRefreshToken[REDACTED]");
    }

    @Test
    void equalityFollowsTheValue() {
        assertThat(PlainRefreshToken.of(FORTY_THREE)).isEqualTo(PlainRefreshToken.of(FORTY_THREE))
                .hasSameHashCodeAs(PlainRefreshToken.of(FORTY_THREE))
                .isNotEqualTo(PlainRefreshToken.of("A".repeat(43))).isNotEqualTo(FORTY_THREE);
    }

    /** A deterministic random: SHA1PRNG seeded before first use never mixes in system entropy. */
    private static SecureRandom seededRandom() {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(20261012L);
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
