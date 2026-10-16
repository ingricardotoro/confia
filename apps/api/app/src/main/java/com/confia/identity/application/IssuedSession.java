package com.confia.identity.application;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.shared.security.token.AccessToken;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A session just issued (session-tokens-and-web-layer design.md decision 9): its identity (the
 * family, which is the {@code sid} of its access tokens), the refresh token in clear text, delivered
 * once, and the access token. Both secrets print redacted, so the record is safe to print.
 */
public record IssuedSession(UUID sessionId, PlainRefreshToken refreshToken, Instant refreshExpiresAt,
        AccessToken accessToken, Instant accessExpiresAt) {

    public IssuedSession {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(refreshToken, "refreshToken");
        Objects.requireNonNull(refreshExpiresAt, "refreshExpiresAt");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(accessExpiresAt, "accessExpiresAt");
    }
}
