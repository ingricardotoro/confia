package com.confia.shared.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one place outside {@code identity} where a SHA-256 is computed (session-tokens-and-web-layer
 * design.md, decision 16). The architecture rule {@code SigningAndHashingConfinementTest} forbids
 * {@code MessageDigest} and every other signing or hashing utility in any package other than
 * {@code com.confia.identity} and {@code com.confia.shared.security}, so a class elsewhere that
 * needs a digest, such as the audit chain serializer, delegates here instead of hashing by itself.
 *
 * <p>Pure and stateless: a new {@link MessageDigest} per call, so it is safe from any thread.
 */
public final class Digests {

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
}
