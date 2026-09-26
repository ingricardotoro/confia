package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The persisted shape of {@code identity_login_backoff}'s two mutable columns
 * (design.md, decision 3): how many consecutive failures a presented identifier has accumulated,
 * and the instant of its last attempt. Indexed by an {@link IdentifierFingerprint}, never by an
 * account, so this state exists just as much for an identifier that corresponds to no account at
 * all (specs/identity/spec.md, "El estado del retroceso existe para un identificador sin cuenta").
 *
 * <p>Not a secret in the sense {@code docs/03-seguridad.md} §4.1/§4.6 or CLAUDE.md regla 11 name —
 * it carries no password, hash or pepper — so this record keeps its generated {@code toString()},
 * unlike {@link PlainPassword}, {@link StoredPasswordHash} or {@code Argon2Pepper} (design.md,
 * decision 10, point 1).
 */
public record BackoffState(int consecutiveFailures, Instant lastAttemptAt) {

    public BackoffState {
        if (consecutiveFailures < 0) {
            throw new IllegalArgumentException(
                    "consecutiveFailures must not be negative, was " + consecutiveFailures);
        }
        Objects.requireNonNull(lastAttemptAt, "lastAttemptAt");
    }

    /**
     * The state a presented identifier has before its very first recorded attempt — exactly what
     * the reclaim statement's own default row represents (design.md, decision 5: {@code VALUES (?,
     * ?, 0, ?)}).
     */
    public static BackoffState initial(Instant now) {
        return new BackoffState(0, now);
    }
}
