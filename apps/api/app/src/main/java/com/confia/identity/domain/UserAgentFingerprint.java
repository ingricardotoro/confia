package com.confia.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A digest of the {@code User-Agent} of the client that opened a session: the SHA-256 of the agent
 * text the request filter already sanitized, as 64 lowercase hexadecimal characters, and the SHA-256
 * of the empty text when there was no agent (session-tokens-and-web-layer design.md decision 8;
 * scenarios I119 and I120). The agent itself is never stored. The fingerprint recognizes "the same
 * device" for the grace window of a later change and is never written to a log or to the audit trail.
 */
public final class UserAgentFingerprint {

    private static final Pattern SIXTY_FOUR_LOWERCASE_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final String value;

    /**
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not exactly 64 lowercase hex characters
     */
    public UserAgentFingerprint(String value) {
        Objects.requireNonNull(value, "value");
        if (!SIXTY_FOUR_LOWERCASE_HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a user-agent fingerprint must be exactly 64 lowercase hex characters");
        }
        this.value = value;
    }

    /** The fingerprint of {@code userAgent}; a {@code null} agent has the fingerprint of no text. */
    public static UserAgentFingerprint of(String userAgent) {
        String text = userAgent == null ? "" : userAgent;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return new UserAgentFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must support SHA-256", e);
        }
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserAgentFingerprint that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "UserAgentFingerprint[REDACTED]";
    }
}
