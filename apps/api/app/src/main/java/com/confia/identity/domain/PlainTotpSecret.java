package com.confia.identity.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * A TOTP secret in clear text, 20 random bytes (specs/identity/spec.md, requirement "Inscripción y
 * verificación del segundo factor TOTP...", "un secreto TOTP de 20 bytes aleatorios"). {@code
 * toString()} is deliberately redacted (CLAUDE.md, regla 11; column-encryption-and-mfa-totp
 * design.md, decision 9): this is exactly "el secreto TOTP en claro" the redaction requirement
 * names.
 *
 * <p><b>A final class with an explicit override, never a {@code record}</b> — but not for the
 * reason an earlier version of this Javadoc gave. A record's generated {@code toString()} would
 * <em>not</em> print these bytes verbatim: for an array component it prints an identity hash such
 * as {@code [B@1b6d3586}, because it delegates to {@code String.valueOf}. The real reason is that
 * such a class would be safe only by accident of how the JVM renders arrays, and would start
 * leaking the moment someone added a {@code String} or {@code char[]} component — which is how
 * {@code AuthenticationCommand} leaked a password through three reviews in part 1. Here the
 * redaction is stated, not inherited from an implementation detail.
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

    /**
     * The secret rendered for a human to transcribe into an authenticator app: base32 as RFC 4648
     * defines it, which is what those apps accept as manual entry (specs/identity/spec.md, escenario
     * "La inscripción devuelve el secreto en base32 una única vez"). Always exactly 32 characters
     * with no padding, because {@value #LENGTH_BYTES} bytes is a multiple of five.
     *
     * <p><b>This is the one method that hands the secret out legibly</b>, and it exists because
     * without it no authenticator app could ever learn the secret. It is called once, in the return
     * of enrollment; nothing reads the secret in clear afterwards.
     */
    public String base32() {
        return Base32.encode(value);
    }

    /** Redacted deliberately: {@code value} is the clear-text TOTP secret itself. */
    @Override
    public String toString() {
        return "PlainTotpSecret[REDACTED]";
    }
}
