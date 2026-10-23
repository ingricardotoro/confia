package com.confia.identity.application;

import java.time.Instant;
import java.util.Objects;

/**
 * The session a login hands to the client (session-endpoints-cookie-and-csrf design.md decision
 * 10): the access token, the refresh token and when each of the three ends comes, as plain text and
 * instants the web layer can copy without importing a token type or a domain type.
 *
 * <p>Never a {@code record}: it carries two secrets, and the {@code toString()} a record generates
 * prints every component (the lesson of {@code ResetPasswordCommand}). Both tokens print as
 * {@code REDACTED}.
 */
public final class SessionGrant {

    private final String accessToken;
    private final Instant accessTokenExpiresAt;
    private final String refreshToken;
    private final Instant sessionExpiresAt;
    private final Instant idleExpiresAt;

    public SessionGrant(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
            Instant sessionExpiresAt, Instant idleExpiresAt) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.accessTokenExpiresAt = Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt");
        this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
        this.sessionExpiresAt = Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        this.idleExpiresAt = Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
    }

    /** The compact access token. */
    public String accessToken() {
        return accessToken;
    }

    public Instant accessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    /** The refresh token in clear text, delivered once. */
    public String refreshToken() {
        return refreshToken;
    }

    /** The end of the session, whatever its activity. */
    public Instant sessionExpiresAt() {
        return sessionExpiresAt;
    }

    /** The end of the session if nothing refreshes it before. */
    public Instant idleExpiresAt() {
        return idleExpiresAt;
    }

    @Override
    public String toString() {
        return "SessionGrant[accessToken=REDACTED, refreshToken=REDACTED, accessTokenExpiresAt="
                + accessTokenExpiresAt + ", sessionExpiresAt=" + sessionExpiresAt
                + ", idleExpiresAt=" + idleExpiresAt + "]";
    }
}
