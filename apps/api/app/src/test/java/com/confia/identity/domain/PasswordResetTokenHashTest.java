package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordResetTokenHash} (password-recovery-token design.md decisions 3 and 10;
 * specs/identity/spec.md, "Solo SHA-256"): the only form of a reset token that is ever stored or
 * looked up. The accepted shape is exactly the one {@code identity_password_reset_token_hash_chk}
 * enforces, and neither its {@code toString()} nor a rejection message ever carries the value.
 * {@link PasswordResetTokenHash#of} is the only way the reset derives one from a presented token.
 */
class PasswordResetTokenHashTest {

    private static final String SIXTY_FOUR_HEX = "0123456789abcdef".repeat(4);

    @Test
    void acceptsExactlySixtyFourLowercaseHexCharacters() {
        PasswordResetTokenHash hash = new PasswordResetTokenHash(SIXTY_FOUR_HEX);

        assertThat(hash.value()).isEqualTo(SIXTY_FOUR_HEX);
    }

    @Test
    void rejectsSixtyThreeAndSixtyFiveCharacters() {
        assertThatThrownBy(() -> new PasswordResetTokenHash(SIXTY_FOUR_HEX.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetTokenHash(SIXTY_FOUR_HEX + "0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUppercaseHexBecauseTheCatalogueOnlyAcceptsLowercase() {
        assertThatThrownBy(() -> new PasswordResetTokenHash(SIXTY_FOUR_HEX.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheBase64UrlTokenItselfAndNull() {
        assertThatThrownBy(() -> new PasswordResetTokenHash(
                "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetTokenHash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRejectionMessageNeverCarriesTheReceivedValue() {
        String almostAHash = "f".repeat(63) + "G";

        assertThatThrownBy(() -> new PasswordResetTokenHash(almostAHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(almostAHash)
                .hasMessageNotContaining("fffff");
    }

    @Test
    void toStringIsRedacted() {
        PasswordResetTokenHash hash = new PasswordResetTokenHash(SIXTY_FOUR_HEX);

        assertThat(hash.toString())
                .doesNotContain(SIXTY_FOUR_HEX)
                .doesNotContain("0123456789")
                .isEqualTo("PasswordResetTokenHash[REDACTED]");
    }

    /**
     * {@code of(token)} is SHA-256 over the ASCII bytes of the base64url text, as lowercase hex
     * (design.md, section 0: the reading fixed for "SHA-256 del token"). The expected value is
     * computed here independently with {@link MessageDigest} and {@link HexFormat}.
     */
    @Test
    void ofComputesTheSha256OfTheAsciiTextOfTheToken() throws Exception {
        String text = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.US_ASCII)));

        PasswordResetTokenHash hash = PasswordResetTokenHash.of(PlainPasswordResetToken.of(text));

        assertThat(hash.value()).isEqualTo(expected).hasSize(64);
    }

    @Test
    void theHashOfTheSameTokenIsStableAndTwoTokensHashDifferently() {
        PlainPasswordResetToken token = PlainPasswordResetToken.of("A".repeat(43));

        assertThat(PasswordResetTokenHash.of(token)).isEqualTo(PasswordResetTokenHash.of(token))
                .isNotEqualTo(PasswordResetTokenHash.of(PlainPasswordResetToken.of("B".repeat(43))));
    }

    @Test
    void equalityFollowsTheValue() {
        PasswordResetTokenHash hash = new PasswordResetTokenHash(SIXTY_FOUR_HEX);

        assertThat(hash).isEqualTo(new PasswordResetTokenHash(SIXTY_FOUR_HEX))
                .hasSameHashCodeAs(new PasswordResetTokenHash(SIXTY_FOUR_HEX))
                .isNotEqualTo(new PasswordResetTokenHash("f".repeat(64)))
                .isNotEqualTo(SIXTY_FOUR_HEX);
    }
}
