package com.confia.identity.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The SHA-256 of a password-reset token, as 64 lowercase hexadecimal characters
 * (password-recovery-token design.md decision 3; specs/identity/spec.md, "Solo SHA-256"). It is the
 * only form of a token that is ever stored or looked up, and the accepted shape is exactly the one
 * {@code identity_password_reset_token_hash_chk} enforces in {@code V7}.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>: the delta
 * forbids the hash from being observable, and a record would print it verbatim. The rejection
 * message states the rule and never the received value, which may be a token pasted where its hash
 * belongs. Computing the hash from a token arrives with the token class itself (task 2.2).
 */
public final class PasswordResetTokenHash {

    private static final Pattern SIXTY_FOUR_LOWERCASE_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly 64 lowercase hex characters
     */
    public PasswordResetTokenHash(String value) {
        Objects.requireNonNull(value, "value");
        if (!SIXTY_FOUR_LOWERCASE_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a password-reset token hash must be exactly 64 lowercase hex characters");
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PasswordResetTokenHash that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: the delta forbids the stored hash from being observable. */
    @Override
    public String toString() {
        return "PasswordResetTokenHash[REDACTED]";
    }
}
