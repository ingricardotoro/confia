package com.confia.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The single source of every session duration (session-tokens-and-web-layer design.md decision 8;
 * decision 4 of the owner): the family lasts 12 hours from its creation, a refresh token lasts 8
 * hours and never outlives its family, and a session with no use for 30 minutes is idle. No
 * {@code CHECK} of {@code V8} repeats these durations. It has no clock of its own and receives every
 * instant, as {@link PasswordResetTokenPolicy} does.
 */
public final class SessionLifetimePolicy {

    public static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(12);
    public static final Duration REFRESH_LIFETIME = Duration.ofHours(8);
    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    /** When a family created at {@code createdAt} ends, whatever its use. */
    public Instant absoluteExpiresAt(Instant createdAt) {
        return Objects.requireNonNull(createdAt, "createdAt").plus(ABSOLUTE_LIFETIME);
    }

    /**
     * When a refresh token issued at {@code issuedAt} expires: eight hours later, or the end of its
     * family when that comes first.
     *
     * @throws IllegalArgumentException if the family has already ended at {@code issuedAt}
     */
    public Instant refreshExpiresAt(Instant issuedAt, Instant absoluteExpiresAt) {
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (!issuedAt.isBefore(absoluteExpiresAt)) {
            throw new IllegalArgumentException("a refresh token cannot be issued after the end of "
                    + "its family");
        }
        Instant byLifetime = issuedAt.plus(REFRESH_LIFETIME);
        return byLifetime.isBefore(absoluteExpiresAt) ? byLifetime : absoluteExpiresAt;
    }

    /** Whether a session last used at {@code lastUsedAt} is idle at {@code now}; the border is idle. */
    public boolean isIdle(Instant lastUsedAt, Instant now) {
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(now, "now");
        return !now.isBefore(lastUsedAt.plus(IDLE_TIMEOUT));
    }
}
