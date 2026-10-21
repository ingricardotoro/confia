package com.confia.shared.security.csrf;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keys generated at run time for the CSRF tests. Nothing here is a value of the repository: every
 * call returns fresh random bytes (CLAUDE.md, regla 13).
 */
final class CsrfKeyFixtures {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfKeyFixtures() {
    }

    /** A fresh random key of {@code length} bytes. */
    static byte[] newKey(int length) {
        byte[] key = new byte[length];
        RANDOM.nextBytes(key);
        return key;
    }

    /** A fresh random key of the minimum length. */
    static byte[] newKey() {
        return newKey(CsrfKeys.MIN_KEY_BYTES);
    }

    /** {@code key} in standard Base64, the form an environment carries. */
    static String base64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    /** A fresh random refresh token of the shape the session change issues: 43 base64url characters. */
    static String newRefreshToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(newKey(32));
    }
}
