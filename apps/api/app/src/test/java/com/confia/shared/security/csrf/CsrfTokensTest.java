package com.confia.shared.security.csrf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, "Las rutas de cookie exigen un token CSRF de doble envío vinculado al refresco con
 * HMAC" (session-endpoints-cookie-and-csrf design.md, decision 3): the token is
 * {@code B64U(r).B64U(HMAC-SHA256(k, r || SHA-256(refresh)))}, every rejection is {@code false} and
 * never an exception, and the previous key verifies while only the current one issues. The expected
 * MAC is computed here with the JDK, independently of the class under test, so the formula is
 * pinned and not merely self-consistent.
 */
class CsrfTokensTest {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final Base64.Encoder B64U = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64U_DECODER = Base64.getUrlDecoder();

    private final byte[] key = CsrfKeyFixtures.newKey();
    private final CsrfTokens tokens = new CsrfTokens(CsrfKeys.of(key, null));
    private final String refresh = CsrfKeyFixtures.newRefreshToken();

    @Test
    void anIssuedTokenHasTwoParts43CharactersLongInTheBase64UrlAlphabet() {
        String token = tokens.issue(refresh);

        assertThat(token).matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
    }

    @Test
    void theMacIsTheHmacSha256OfTheRandomPartFollowedByTheSha256OfTheRefreshToken() throws Exception {
        String token = tokens.issue(refresh);
        String[] parts = token.split("\\.");
        byte[] random = B64U_DECODER.decode(parts[0]);
        byte[] refreshHash = Digests.sha256(refresh.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(random);
        mac.update(refreshHash);

        assertThat(random).hasSize(32);
        assertThat(parts[1]).isEqualTo(B64U.encodeToString(mac.doFinal()));
    }

    @Test
    void anIssuedTokenVerifiesAgainstTheRefreshTokenItWasIssuedFor() {
        assertThat(tokens.verify(tokens.issue(refresh), refresh)).isTrue();
    }

    @Test
    void twoTokensForTheSameRefreshTokenDifferAndBothVerify() {
        String first = tokens.issue(refresh);
        String second = tokens.issue(refresh);

        assertThat(first).isNotEqualTo(second);
        assertThat(tokens.verify(first, refresh)).isTrue();
        assertThat(tokens.verify(second, refresh)).isTrue();
    }

    @Test
    void aTokenBoundToAnotherRefreshTokenIsRejected() {
        String other = CsrfKeyFixtures.newRefreshToken();

        assertThat(tokens.verify(tokens.issue(other), refresh)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 20, 42})
    void aTokenWithTheRandomPartAlteredInOnePositionIsRejected(int position) {
        String token = tokens.issue(refresh);

        assertThat(tokens.verify(alter(token, position), refresh)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {44, 45, 70, 86})
    void aTokenWithTheMacAlteredInOnePositionIsRejected(int position) {
        String token = tokens.issue(refresh);

        assertThat(tokens.verify(alter(token, position), refresh)).isFalse();
    }

    @Test
    void aTokenWithTheFirstAndTheLastByteOfTheMacChangedByOneBitIsRejected() {
        String token = tokens.issue(refresh);
        String[] parts = token.split("\\.");
        byte[] mac = B64U_DECODER.decode(parts[1]);
        byte[] first = mac.clone();
        first[0] ^= 0x01;
        byte[] last = mac.clone();
        last[last.length - 1] ^= (byte) 0x80;

        assertThat(tokens.verify(parts[0] + "." + B64U.encodeToString(first), refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + "." + B64U.encodeToString(last), refresh)).isFalse();
    }

    @Test
    void aTokenWithTheRandomPartChangedByOneBitIsRejected() {
        String token = tokens.issue(refresh);
        String[] parts = token.split("\\.");
        byte[] random = B64U_DECODER.decode(parts[0]);
        random[random.length - 1] ^= 0x01;

        assertThat(tokens.verify(B64U.encodeToString(random) + "." + parts[1], refresh)).isFalse();
    }

    @Test
    void aTokenIssuedWithAnotherKeyIsRejected() {
        CsrfTokens stranger = new CsrfTokens(CsrfKeys.of(CsrfKeyFixtures.newKey(), null));

        assertThat(tokens.verify(stranger.issue(refresh), refresh)).isFalse();
    }

    @Test
    void theCurrentKeyAlsoVerifiesWhenAPreviousKeyIsConfigured() {
        CsrfTokens rotated = new CsrfTokens(CsrfKeys.of(key, CsrfKeyFixtures.newKey()));

        assertThat(rotated.verify(rotated.issue(refresh), refresh)).isTrue();
    }

    @Test
    void aTokenIssuedWithThePreviousKeyIsAcceptedAndNewOnesAreIssuedWithTheCurrentKey() {
        byte[] previousKey = CsrfKeyFixtures.newKey();
        String oldToken = new CsrfTokens(CsrfKeys.of(previousKey, null)).issue(refresh);
        CsrfTokens rotated = new CsrfTokens(CsrfKeys.of(key, previousKey));
        CsrfTokens currentOnly = new CsrfTokens(CsrfKeys.of(key, null));
        CsrfTokens previousOnly = new CsrfTokens(CsrfKeys.of(previousKey, null));

        assertThat(rotated.verify(oldToken, refresh)).isTrue();
        String reissued = rotated.issue(refresh);
        assertThat(currentOnly.verify(reissued, refresh)).as("issued with the current key").isTrue();
        assertThat(previousOnly.verify(reissued, refresh)).as("not with the previous one").isFalse();
    }

    @Test
    void withoutAPreviousKeyATokenOfTheOldKeyIsRejected() {
        byte[] previousKey = CsrfKeyFixtures.newKey();
        String oldToken = new CsrfTokens(CsrfKeys.of(previousKey, null)).issue(refresh);

        assertThat(tokens.verify(oldToken, refresh)).isFalse();
    }

    @Test
    void aTokenOfAKeyThatIsNeitherTheCurrentNorThePreviousIsRejected() {
        CsrfTokens rotated = new CsrfTokens(CsrfKeys.of(key, CsrfKeyFixtures.newKey()));
        String stranger = new CsrfTokens(CsrfKeys.of(CsrfKeyFixtures.newKey(), null)).issue(refresh);

        assertThat(rotated.verify(stranger, refresh)).isFalse();
    }

    @Test
    void aTokenWithTheWrongLengthIsRejected() {
        String token = tokens.issue(refresh);

        assertThat(tokens.verify(token.substring(1), refresh)).as("86 characters").isFalse();
        assertThat(tokens.verify(token + "A", refresh)).as("88 characters").isFalse();
        assertThat(tokens.verify("", refresh)).isFalse();
        assertThat(tokens.verify(".", refresh)).isFalse();
    }

    @Test
    void aTokenWithPaddingIsRejected() {
        String[] parts = tokens.issue(refresh).split("\\.");

        assertThat(tokens.verify(parts[0] + "=." + parts[1], refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + "." + parts[1] + "=", refresh)).isFalse();
        assertThat(tokens.verify(parts[0].substring(1) + "=." + parts[1], refresh)).isFalse();
    }

    @Test
    void aTokenInTheStandardAlphabetIsRejected() {
        String[] parts = tokens.issue(refresh).split("\\.");

        assertThat(tokens.verify(replaceAt(parts[0], 5, '+') + "." + parts[1], refresh)).isFalse();
        assertThat(tokens.verify(replaceAt(parts[0], 5, '/') + "." + parts[1], refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + "." + replaceAt(parts[1], 7, '+'), refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + "." + replaceAt(parts[1], 7, '/'), refresh)).isFalse();
    }

    @Test
    void aTokenWithNonZeroTrailingBitsIsRejectedEvenThoughTheJdkDecodesItToTheSameBytes() {
        // 32 bytes leave two spare bits in the 43rd character. The JDK decoder ignores them, so a
        // second text would decode to the very same bytes unless the canonical check refuses it.
        String[] parts = tokens.issue(refresh).split("\\.");
        String forgedRandom = withTrailingBits(parts[0], 0x01);
        String forgedMac = withTrailingBits(parts[1], 0x02);

        assertThat(forgedRandom).isNotEqualTo(parts[0]);
        assertThat(B64U_DECODER.decode(forgedRandom))
                .as("the JDK reads the forged text as the same bytes")
                .isEqualTo(B64U_DECODER.decode(parts[0]));
        assertThat(tokens.verify(forgedRandom + "." + parts[1], refresh)).isFalse();
        assertThat(B64U_DECODER.decode(forgedMac)).isEqualTo(B64U_DECODER.decode(parts[1]));
        assertThat(tokens.verify(parts[0] + "." + forgedMac, refresh)).isFalse();
    }

    @Test
    void aTokenWithoutADotOrWithMoreThanOneIsRejected() {
        String[] parts = tokens.issue(refresh).split("\\.");

        assertThat(tokens.verify(parts[0] + "-" + parts[1], refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + "." + parts[1].substring(0, 41) + ".A", refresh)).isFalse();
        assertThat(tokens.verify(parts[0] + parts[1] + ".", refresh)).isFalse();
    }

    @Test
    void everyRejectionIsFalseAndNeverAnException() {
        String token = tokens.issue(refresh);

        assertThatCode(() -> {
            assertThat(tokens.verify(null, refresh)).isFalse();
            assertThat(tokens.verify(token, null)).isFalse();
            assertThat(tokens.verify("not a token at all, with spaces and é", refresh)).isFalse();
            assertThat(tokens.verify("\u0000".repeat(87), refresh)).isFalse();
            assertThat(tokens.verify("A".repeat(43) + "." + "A".repeat(43), refresh)).isFalse();
            assertThat(tokens.verify(token, "")).isFalse();
        }).doesNotThrowAnyException();
    }

    @Test
    void theDoubleSubmitNeedsTheCookieAndTheHeaderEqualAndValidForTheRefreshToken() {
        String token = tokens.issue(refresh);
        String second = tokens.issue(refresh);

        assertThat(tokens.verifyDoubleSubmit(token, token, refresh)).isTrue();
        assertThat(tokens.verifyDoubleSubmit(token, second, refresh))
                .as("both valid but different").isFalse();
        assertThat(tokens.verifyDoubleSubmit(second, token, refresh)).isFalse();
        assertThat(tokens.verifyDoubleSubmit(token, token, CsrfKeyFixtures.newRefreshToken()))
                .as("equal but bound to another refresh token").isFalse();
        assertThat(tokens.verifyDoubleSubmit(null, token, refresh)).isFalse();
        assertThat(tokens.verifyDoubleSubmit(token, null, refresh)).isFalse();
        assertThat(tokens.verifyDoubleSubmit("", "", refresh)).isFalse();
        assertThat(tokens.verifyDoubleSubmit(token, token, null)).isFalse();
    }

    @Test
    void issuingForANullRefreshTokenIsAProgrammingError() {
        assertThatThrownBy(() -> tokens.issue(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theRefreshTokenIsHashedAsUtf8SoANonAsciiTextStillBindsExactly() {
        String text = "café-" + refresh;

        assertThat(tokens.verify(tokens.issue(text), text)).isTrue();
        assertThat(tokens.verify(tokens.issue(text), "cafe-" + refresh)).isFalse();
    }

    @Test
    void theTokenDoesNotContainTheKeyOrTheRefreshToken() {
        String token = tokens.issue(refresh);

        assertThat(token).doesNotContain(refresh).doesNotContain(CsrfKeyFixtures.base64(key));
    }

    /** {@code part} with the spare low bits of its last character set to {@code bits}. */
    private static String withTrailingBits(String part, int bits) {
        int index = ALPHABET.indexOf(part.charAt(42));
        return part.substring(0, 42) + ALPHABET.charAt(index | bits);
    }

    private static String alter(String token, int position) {
        char original = token.charAt(position);
        char replacement = original == 'A' ? 'B' : 'A';
        return replaceAt(token, position, replacement);
    }

    private static String replaceAt(String text, int position, char replacement) {
        return text.substring(0, position) + replacement + text.substring(position + 1);
    }
}
