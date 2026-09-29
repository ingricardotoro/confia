package com.confia.shared.crypto;

import java.util.Base64;
import java.util.Objects;

/**
 * The column-encryption master key (KEK), which wraps every data-encryption key
 * (column-encryption-and-mfa-totp design.md, decisions 1, 2 and 4; ADR-0023). {@code toString()}
 * is deliberately redacted (CLAUDE.md, regla 11), by explicit override rather than a {@code
 * record} — the literal precedent for this class is {@code
 * com.confia.identity.infrastructure.Argon2Pepper} (D1 of the proposal).
 *
 * <p><b>How the Base64 value reaches {@link #fromBase64} is deliberately not this class's
 * concern.</b> Real wiring — an environment variable, a mounted secret, or a real secrets manager
 * — is deferred exactly as {@code Argon2Pepper}'s own Javadoc defers it, to whichever later change
 * declares a real production configuration. This class only ever validates that whatever arrives
 * decodes to exactly {@value #LENGTH_BYTES} bytes, and fails loudly at construction if it does
 * not — never later, on first use.
 */
public final class ColumnEncryptionMasterKey {

    public static final int LENGTH_BYTES = 32;

    private final byte[] value;

    private ColumnEncryptionMasterKey(byte[] value) {
        this.value = value;
    }

    /**
     * @throws NullPointerException if {@code base64} is {@code null}
     * @throws IllegalArgumentException if {@code base64} is blank, is not valid Base64, or does
     *     not decode to exactly {@value #LENGTH_BYTES} bytes
     */
    public static ColumnEncryptionMasterKey fromBase64(String base64) {
        Objects.requireNonNull(base64, "base64");
        if (base64.isBlank()) {
            throw new IllegalArgumentException(
                    "the column-encryption master key must not be blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "the column-encryption master key is not valid Base64", e);
        }
        if (decoded.length != LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "the column-encryption master key must decode to exactly " + LENGTH_BYTES
                            + " bytes, was " + decoded.length + " bytes");
        }
        return new ColumnEncryptionMasterKey(decoded);
    }

    /** Returns a defensive copy: callers must never be able to mutate the master key this instance
     * holds. */
    public byte[] value() {
        return value.clone();
    }

    /** Redacted deliberately: this value is the column-encryption master key itself. */
    @Override
    public String toString() {
        return "ColumnEncryptionMasterKey[REDACTED]";
    }
}
