package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link RefreshTokenHash} (session-tokens-and-web-layer design.md decisions 8 and 9; scenario I88,
 * ADR-0005 test 5): the SHA-256 of the delivered text, as the 64 lowercase hex characters {@code
 * identity_refresh_token_hash_chk} accepts, and never observable.
 */
class RefreshTokenHashTest {

    private static final String SIXTY_FOUR_HEX = "0123456789abcdef".repeat(4);

    @Test
    void acceptsExactlySixtyFourLowercaseHexCharacters() {
        assertThat(new RefreshTokenHash(SIXTY_FOUR_HEX).value()).isEqualTo(SIXTY_FOUR_HEX);
        assertThatThrownBy(() -> new RefreshTokenHash(SIXTY_FOUR_HEX.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenHash(SIXTY_FOUR_HEX + "0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenHash(SIXTY_FOUR_HEX.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenHash(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRejectionMessageNeverCarriesTheReceivedValue() {
        String almostAHash = "f".repeat(63) + "G";

        assertThatThrownBy(() -> new RefreshTokenHash(almostAHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(almostAHash).hasMessageNotContaining("fffff");
    }

    @Test
    void ofComputesTheSha256OfTheAsciiTextOfTheToken() throws Exception {
        String text = "Zk3vQ8-rT1mYw6_NcB9sHd2LpXe4GfJ7uAiO0aRtVyE";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.US_ASCII)));

        RefreshTokenHash hash = RefreshTokenHash.of(PlainRefreshToken.of(text));

        assertThat(hash.value()).isEqualTo(expected).hasSize(64).isNotEqualTo(text);
    }

    @Test
    void theHashOfTheSameTokenIsStableAndTwoTokensHashDifferently() {
        PlainRefreshToken token = PlainRefreshToken.of("A".repeat(43));

        assertThat(RefreshTokenHash.of(token)).isEqualTo(RefreshTokenHash.of(token))
                .isNotEqualTo(RefreshTokenHash.of(PlainRefreshToken.of("B".repeat(43))));
    }

    @Test
    void toStringIsRedactedAndEqualityFollowsTheValue() {
        RefreshTokenHash hash = new RefreshTokenHash(SIXTY_FOUR_HEX);

        assertThat(hash.toString()).doesNotContain("0123456789")
                .isEqualTo("RefreshTokenHash[REDACTED]");
        assertThat(hash).isEqualTo(new RefreshTokenHash(SIXTY_FOUR_HEX))
                .hasSameHashCodeAs(new RefreshTokenHash(SIXTY_FOUR_HEX))
                .isNotEqualTo(new RefreshTokenHash("f".repeat(64))).isNotEqualTo(SIXTY_FOUR_HEX);
    }
}
