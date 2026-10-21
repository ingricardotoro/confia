package com.confia.shared.security.csrf;

import com.confia.shared.security.Digests;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The CSRF token of the double-submit cookie, bound to the refresh token (session-endpoints-cookie-
 * and-csrf design.md, decision 3): {@code B64U(r) "." B64U(HMAC-SHA256(k, r || SHA-256(refresh)))},
 * with {@code r} 32 bytes from {@link SecureRandom} and each part 43 characters of base64url
 * without padding.
 *
 * <p>The token is bound to the hash of the refresh token and not to the session id, so it verifies
 * without a database read and without an access token, which a refresh request does not carry. A
 * token issued for one refresh token never verifies for another, which is what defeats a token a
 * sibling subdomain planted in the victim's browser.
 *
 * <p>Verification is strict: the length is exact, each part is the canonical base64url of its
 * bytes (no padding, no standard alphabet, no non-zero trailing bits, so one byte string has one
 * text), the MAC is computed with the current key and then with the previous one, and the comparison
 * is {@link MessageDigest#isEqual constant time}. Every rejection is {@code false}: never an
 * exception and never a message that tells a caller why. A {@link Mac} is taken per call because it
 * is not safe between threads.
 */
public final class CsrfTokens {

    private static final String HMAC = "HmacSHA256";
    private static final int RANDOM_BYTES = 32;
    private static final int MAC_BYTES = 32;
    private static final int PART_LENGTH = 43;
    private static final int TOKEN_LENGTH = PART_LENGTH + 1 + PART_LENGTH;
    private static final char SEPARATOR = '.';

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec currentKey;
    private final SecretKeySpec previousKey;

    public CsrfTokens(CsrfKeys keys) {
        this.currentKey = new SecretKeySpec(keys.current(), HMAC);
        this.previousKey = keys.previous().map(bytes -> new SecretKeySpec(bytes, HMAC)).orElse(null);
    }

    /** A new token for {@code refreshToken}, signed with the current key. */
    public String issue(String refreshToken) {
        byte[] refreshHash = hashOf(refreshToken);
        byte[] nonce = new byte[RANDOM_BYTES];
        random.nextBytes(nonce);
        return ENCODER.encodeToString(nonce) + SEPARATOR
                + ENCODER.encodeToString(mac(currentKey, nonce, refreshHash));
    }

    /**
     * Whether {@code presented} is a token issued, with the current or the previous key, for
     * {@code refreshToken}. A {@code null} or malformed token, or a {@code null} refresh token, is
     * {@code false}.
     */
    public boolean verify(String presented, String refreshToken) {
        if (presented == null || refreshToken == null || presented.length() != TOKEN_LENGTH
                || presented.charAt(PART_LENGTH) != SEPARATOR) {
            return false;
        }
        byte[] nonce = canonicalBytes(presented.substring(0, PART_LENGTH), RANDOM_BYTES);
        byte[] presentedMac = canonicalBytes(presented.substring(PART_LENGTH + 1), MAC_BYTES);
        if (nonce == null || presentedMac == null) {
            return false;
        }
        byte[] refreshHash = hashOf(refreshToken);
        boolean current = MessageDigest.isEqual(mac(currentKey, nonce, refreshHash), presentedMac);
        boolean previous = previousKey != null
                && MessageDigest.isEqual(mac(previousKey, nonce, refreshHash), presentedMac);
        return current | previous;
    }

    /**
     * The double-submit check of a cookie route: the CSRF cookie and the {@code X-CSRF-Token} header
     * carry the same text, compared in constant time, and that text is a token issued for {@code
     * refreshToken}. Any {@code null} is {@code false}.
     */
    public boolean verifyDoubleSubmit(String cookieValue, String headerValue, String refreshToken) {
        if (cookieValue == null || headerValue == null) {
            return false;
        }
        boolean equal = MessageDigest.isEqual(cookieValue.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8));
        return equal & verify(headerValue, refreshToken);
    }

    /**
     * The bytes {@code text} encodes, or {@code null} when it is not the canonical base64url,
     * without padding, of exactly {@code length} bytes.
     */
    private static byte[] canonicalBytes(String text, int length) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return bytes.length == length && ENCODER.encodeToString(bytes).equals(text) ? bytes : null;
    }

    private static byte[] hashOf(String refreshToken) {
        return Digests.sha256(Objects.requireNonNull(refreshToken, "refreshToken")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] mac(SecretKeySpec key, byte[] nonce, byte[] refreshHash) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            mac.update(nonce);
            mac.update(refreshHash);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 must be available on every supported JDK", e);
        }
    }
}
