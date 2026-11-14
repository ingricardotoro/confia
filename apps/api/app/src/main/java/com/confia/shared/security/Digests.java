package com.confia.shared.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one place outside {@code identity} where a SHA-256 is computed (session-tokens-and-web-layer
 * design.md, decision 16). The architecture rule {@code SigningAndHashingConfinementTest} forbids
 * {@code MessageDigest} and every other signing or hashing utility in any package other than
 * {@code com.confia.identity} and {@code com.confia.shared.security}, so a class elsewhere that
 * needs a digest, such as the audit chain serializer, delegates here instead of hashing by itself.
 *
 * <p>{@link #hmacSha256} is the keyed counterpart, added for the recipient hash of {@code notifications}
 * (transactional-email-adapter design.md decision 8), which may not hold a {@code Mac} itself.
 *
 * <p>Pure and stateless: a new {@link MessageDigest} or {@link Mac} per call, so it is safe from any thread.
 */
public final class Digests {

    /** The shortest HMAC key accepted, the output size of SHA-256. */
    public static final int MIN_HMAC_KEY_BYTES = 32;

    private static final String HMAC_SHA256 = "HmacSHA256";

    private Digests() {
    }

    /** The 32-byte SHA-256 of {@code data}. */
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every supported JDK", e);
        }
    }

    /**
     * The 32-byte HMAC-SHA-256 of {@code data} under {@code key}.
     *
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code key} has fewer than {@value #MIN_HMAC_KEY_BYTES}
     *     bytes; the message states the rule and never the key
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        if (key.length < MIN_HMAC_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "an HMAC key must have at least " + MIN_HMAC_KEY_BYTES + " bytes");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_SHA256 + " must be available on every supported JDK", e);
        }
    }
}
