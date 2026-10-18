package com.confia.identity.application;

import java.time.Instant;
import java.util.Objects;

/**
 * The successor of a consumed refresh token as it is stored (session-tokens-and-web-layer design.md
 * decision 11): the ciphertext that the grace window may decrypt, and the instant at which the token
 * it holds expires, which a replay hands back as it was first issued.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>: the ciphertext
 * of a session secret is itself a secret (specs/identity/spec.md, requirement "Ningún secreto de
 * sesión es observable"), and a record would print it.
 */
public final class EncryptedSuccessor {

    private final String ciphertext;
    private final Instant expiresAt;

    public EncryptedSuccessor(String ciphertext, Instant expiresAt) {
        this.ciphertext = Objects.requireNonNull(ciphertext, "ciphertext");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public String ciphertext() {
        return ciphertext;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedSuccessor that && ciphertext.equals(that.ciphertext)
                && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ciphertext, expiresAt);
    }

    /** Redacted deliberately: {@code ciphertext} is the stored form of the successor token. */
    @Override
    public String toString() {
        return "EncryptedSuccessor[REDACTED]";
    }
}
