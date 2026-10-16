package com.confia.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The SHA-256 of a refresh token, as 64 lowercase hexadecimal characters
 * (session-tokens-and-web-layer design.md decisions 8 and 9; scenario I88, ADR-0005 test 5). It is
 * the only form of a refresh token that is ever stored or looked up, and the accepted shape is
 * exactly the one {@code identity_refresh_token_hash_chk} enforces in {@code V8}.
 *
 * <p>A final class with a redacted {@code toString()}, never a {@code record}: the hash must not be
 * observable, and the rejection message states the rule and never the received value.
 */
public final class RefreshTokenHash {

    private static final Pattern SIXTY_FOUR_LOWERCASE_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly 64 lowercase hex characters
     */
    public RefreshTokenHash(String value) {
        Objects.requireNonNull(value, "value");
        if (!SIXTY_FOUR_LOWERCASE_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a refresh token hash must be exactly 64 lowercase hex characters");
        }
        this.value = value;
    }

    /** SHA-256 over the ASCII bytes of the token's base64url text, as 64 lowercase hex characters. */
    public static RefreshTokenHash of(PlainRefreshToken token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.value().getBytes(StandardCharsets.US_ASCII));
            return new RefreshTokenHash(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must support SHA-256", e);
        }
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefreshTokenHash that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: the stored hash must not be observable. */
    @Override
    public String toString() {
        return "RefreshTokenHash[REDACTED]";
    }
}
