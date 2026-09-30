package com.confia.identity.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A password-reset token in clear text: 32 random bytes as 43 base64url characters without padding
 * (password-recovery-token design.md decision 3; specs/identity/spec.md, "El token entregado tiene
 * 32 bytes y no se repite"). It exists only between issuance and the link sender, and between the
 * presented request and its hash: {@link PasswordResetTokenHash#of} is all that is ever stored.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>, the lesson of
 * {@code AuthenticationCommand} in part 1. A rejection message states the rule and never the value.
 */
public final class PlainPasswordResetToken {

    public static final int RANDOM_BYTES = 32;

    /** ⌈32 · 4 / 3⌉ base64url characters without padding. */
    public static final int LENGTH_CHARS = 43;

    private static final Pattern BASE64URL_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final String value;

    private PlainPasswordResetToken(String value) {
        this.value = value;
    }

    /** Draws {@value #RANDOM_BYTES} bytes from {@code random} and encodes them as base64url. */
    public static PlainPasswordResetToken generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return new PlainPasswordResetToken(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes));
    }

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not {@value #LENGTH_CHARS} base64url
     *     characters
     */
    public static PlainPasswordResetToken of(String value) {
        Objects.requireNonNull(value, "value");
        if (!BASE64URL_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException("a password-reset token must be exactly "
                    + LENGTH_CHARS + " base64url characters");
        }
        return new PlainPasswordResetToken(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlainPasswordResetToken that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: {@code value} is the clear-text token itself. */
    @Override
    public String toString() {
        return "PlainPasswordResetToken[REDACTED]";
    }
}
