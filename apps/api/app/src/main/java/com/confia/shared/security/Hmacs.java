package com.confia.shared.security;

import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one place outside {@code identity} where an HMAC is computed. The architecture rule
 * {@code SigningAndHashingConfinementTest} keeps {@code javax.crypto.Mac} inside {@code identity} and
 * {@code com.confia.shared.security}, so code elsewhere that needs a keyed digest, such as the blind indexes of
 * {@code shared.crypto}, delegates here instead of hashing by itself or re-implementing the construction.
 *
 * <p>Pure and stateless: a new {@link Mac} per call, so it is safe from any thread.
 */
public final class Hmacs {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private Hmacs() {
    }

    /**
     * The 32-byte HMAC-SHA-256 of {@code message} under {@code key}.
     *
     * @throws IllegalArgumentException if {@code key} is empty
     */
    public static byte[] sha256(byte[] key, byte[] message) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        if (key.length == 0) {
            throw new IllegalArgumentException("An HMAC key must not be empty.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 must be available on every supported JDK", e);
        }
    }
}
