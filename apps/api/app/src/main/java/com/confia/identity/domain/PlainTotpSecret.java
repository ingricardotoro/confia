package com.confia.identity.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * A TOTP secret in clear text, 20 random bytes (specs/identity/spec.md, requirement "Inscripción y
 * verificación del segundo factor TOTP...", "un secreto TOTP de 20 bytes aleatorios"). {@code
 * toString()} is deliberately redacted (CLAUDE.md, regla 11; column-encryption-and-mfa-totp
 * design.md, decision 9): this is exactly "el secreto TOTP en claro" the redaction requirement
 * names, so this is a plain final class with an explicit override, never a {@code record} — whose
 * generated {@code toString()} would print the raw bytes verbatim.
 */
public final class PlainTotpSecret {

    public static final int LENGTH_BYTES = 20;

    private final byte[] value;

    private PlainTotpSecret(byte[] value) {
        this.value = value;
    }

    /** Generates a fresh secret with {@code random} — always {@link #LENGTH_BYTES} bytes. */
    public static PlainTotpSecret generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] bytes = new byte[LENGTH_BYTES];
        random.nextBytes(bytes);
        return new PlainTotpSecret(bytes);
    }

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly {@value #LENGTH_BYTES}
     *     bytes
     */
    public static PlainTotpSecret of(byte[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length != LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "a TOTP secret must be exactly " + LENGTH_BYTES + " bytes, was "
                            + value.length + " bytes");
        }
        return new PlainTotpSecret(value.clone());
    }

    /** Returns a defensive copy: callers must never be able to mutate the secret this instance
     * holds. */
    public byte[] value() {
        return value.clone();
    }

    /** Redacted deliberately: {@code value} is the clear-text TOTP secret itself. */
    @Override
    public String toString() {
        return "PlainTotpSecret[REDACTED]";
    }
}
