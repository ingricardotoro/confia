package com.confia.identity.application;

import java.time.Instant;
import java.util.Objects;

/**
 * What a login hands to a client whose password was right but whose second factor is still owed
 * (session-endpoints-cookie-and-csrf design.md decision 10): the restricted token, as plain text,
 * and what the client must do with it. No session exists behind it.
 *
 * <p>Never a {@code record}, for the reason {@link SessionGrant} gives: the token prints as {@code
 * REDACTED}.
 */
public final class SecondFactorChallenge {

    /** What the second factor of the account asks of the client. */
    public enum Kind {
        /** The account has a verified second factor: the client presents a code. */
        VERIFICATION_REQUIRED,
        /** The account must have a second factor and has none: the client enrolls one. */
        ENROLLMENT_REQUIRED
    }

    private final Kind kind;
    private final String token;
    private final Instant expiresAt;

    public SecondFactorChallenge(Kind kind, String token, Instant expiresAt) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.token = Objects.requireNonNull(token, "token");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public Kind kind() {
        return kind;
    }

    /** The compact restricted token. */
    public String token() {
        return token;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "SecondFactorChallenge[kind=" + kind + ", token=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
