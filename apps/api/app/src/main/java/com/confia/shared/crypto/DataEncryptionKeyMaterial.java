package com.confia.shared.crypto;

import java.util.Objects;

/**
 * A data-encryption key (DEK), already unwrapped from its stored, KEK-encrypted form
 * (column-encryption-and-mfa-totp design.md, decisions 2 and 4). {@code toString()} is
 * deliberately redacted (CLAUDE.md, regla 11; specs/identity/spec.md, "Ningún secreto nuevo de este
 * cambio es observable..."), by explicit override rather than a {@code record}, following {@code
 * com.confia.identity.infrastructure.Argon2Pepper}'s exact precedent — never a bare {@code record},
 * whose generated {@code toString()} would print the raw key bytes verbatim.
 */
public final class DataEncryptionKeyMaterial {

    public static final int LENGTH_BYTES = 32;

    private final DataEncryptionKeyId id;
    private final byte[] rawKeyBytes;

    /**
     * @throws NullPointerException if {@code id} or {@code rawKeyBytes} is {@code null}
     * @throws IllegalArgumentException if {@code rawKeyBytes} is not exactly {@value
     *     #LENGTH_BYTES} bytes
     */
    public DataEncryptionKeyMaterial(DataEncryptionKeyId id, byte[] rawKeyBytes) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rawKeyBytes, "rawKeyBytes");
        if (rawKeyBytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "a data encryption key must be exactly " + LENGTH_BYTES + " bytes, was "
                            + rawKeyBytes.length + " bytes");
        }
        this.rawKeyBytes = rawKeyBytes.clone();
    }

    public DataEncryptionKeyId id() {
        return id;
    }

    /** Returns a defensive copy: callers must never be able to mutate the key material this
     * instance holds. */
    public byte[] rawKeyBytes() {
        return rawKeyBytes.clone();
    }

    /** Redacted deliberately: {@code rawKeyBytes} is the unwrapped data-encryption key itself. */
    @Override
    public String toString() {
        return "DataEncryptionKeyMaterial[id=" + id + ", rawKeyBytes=REDACTED]";
    }
}
