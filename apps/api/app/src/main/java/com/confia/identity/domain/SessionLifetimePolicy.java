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
    public static final Duration GRACE_WINDOW = Duration.ofSeconds(10);

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

    /** Whether a family that ends at {@code absoluteExpiresAt} has ended at {@code now}; the end itself has. */
    public boolean isAbsolutelyExpired(Instant absoluteExpiresAt, Instant now) {
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        Objects.requireNonNull(now, "now");
        return !now.isBefore(absoluteExpiresAt);
    }

    /**
     * Whether a token consumed at {@code consumedAt} is still inside its grace window at {@code now}:
     * strictly less than ten seconds, so the tenth second exactly is already outside.
     */
    public boolean isWithinGraceWindow(Instant consumedAt, Instant now) {
        Objects.requireNonNull(consumedAt, "consumedAt");
        Objects.requireNonNull(now, "now");
        return now.isBefore(consumedAt.plus(GRACE_WINDOW));
    }

    /**
     * The latest consumption instant whose grace window is already closed at {@code now}: a token
     * consumed at or before it was consumed ten seconds ago or more, so its encrypted successor can no
     * longer be replayed and is emptied (design.md decision 10, step 5). It is the same border as
     * {@link #isWithinGraceWindow}: the instant itself is outside.
     */
    public Instant latestConsumptionOutsideGraceWindow(Instant now) {
        return Objects.requireNonNull(now, "now").minus(GRACE_WINDOW);
    }

    /**
     * Whether a family is a live session of {@code owner} at {@code now} (design.md decision 7): it
     * belongs to that account, has not been revoked, has not reached its absolute end and has not been
     * idle for the whole inactivity period. Every border is the one of the method that owns it.
     */
    public boolean isLive(RefreshTokenFamilyState family, StaffAccountId owner, Instant now) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(now, "now");
        return family.accountId().equals(owner) && !family.isRevoked()
                && !isAbsolutelyExpired(family.absoluteExpiresAt(), now)
                && !isIdle(family.lastUsedAt(), now);
    }

    /** Whether a session last used at {@code lastUsedAt} is idle at {@code now}; the border is idle. */
    public boolean isIdle(Instant lastUsedAt, Instant now) {
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(now, "now");
        return !now.isBefore(lastUsedAt.plus(IDLE_TIMEOUT));
    }
}
