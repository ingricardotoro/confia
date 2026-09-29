package com.confia.identity.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * A single MFA recovery code in clear text, ten characters long (specs/identity/spec.md,
 * requirement "Diez códigos de recuperación de MFA de un solo uso, hasheados con Argon2id":
 * "diez códigos de recuperación de MFA de un solo uso, de diez caracteres cada uno"). {@code
 * toString()} is deliberately redacted (CLAUDE.md, regla 11; column-encryption-and-mfa-totp
 * design.md, decision 9): this is exactly "el código de recuperación de MFA en claro" the
 * redaction requirement names.
 *
 * <p><b>A final class with an explicit override, never a {@code record}</b> — and, unlike {@link
 * PlainTotpSecret}, this one matters for the opposite reason that class's own Javadoc warns
 * about: this class carries a {@code String}, not a {@code byte[]}. A record's generated {@code
 * toString()} delegates to each component's own {@code toString()}; for a {@code String}
 * component that prints the value verbatim, so a bare {@code record PlainRecoveryCode(String
 * value)} would leak the code for real, immediately, the same way {@code
 * AuthenticationCommand}'s password field did in part 1.
 *
 * <p><b>The alphabet</b> (a decision this phase made, not fixed by design.md): thirty-two
 * characters — uppercase letters and digits, excluding {@code I}, {@code L}, {@code O}, {@code 0}
 * and {@code 1} — because these codes are shown to a person once and typed back later; the
 * excluded characters are the ones most often confused with one another or with each other's
 * shapes when handwritten or read off a screen. Ten characters from a thirty-two-symbol alphabet
 * give 2^50 possible codes, far beyond what collision or brute-force risk requires here — the real
 * protection against brute force is that a code is checked against a stored Argon2id hash, never
 * against a fast comparison, exactly as {@code PasswordHasher} already protects passwords.
 */
public final class PlainRecoveryCode {

    public static final int LENGTH_CHARS = 10;

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final String value;

    private PlainRecoveryCode(String value) {
        this.value = value;
    }

    /** Generates a fresh recovery code with {@code random} — always {@link #LENGTH_CHARS}
     * characters, drawn from {@link #ALPHABET}. */
    public static PlainRecoveryCode generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        StringBuilder builder = new StringBuilder(LENGTH_CHARS);
        for (int i = 0; i < LENGTH_CHARS; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return new PlainRecoveryCode(builder.toString());
    }

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly {@value #LENGTH_CHARS}
     *     characters
     */
    public static PlainRecoveryCode of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() != LENGTH_CHARS) {
            throw new IllegalArgumentException(
                    "a recovery code must be exactly " + LENGTH_CHARS + " characters, was "
                            + value.length() + " characters");
        }
        return new PlainRecoveryCode(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlainRecoveryCode that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: {@code value} is the clear-text recovery code itself. */
    @Override
    public String toString() {
        return "PlainRecoveryCode[REDACTED]";
    }
}
