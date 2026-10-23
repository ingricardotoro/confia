package com.confia.identity.application;

import com.confia.identity.application.RefreshOutcome.RotationKind;
import java.util.Objects;

/**
 * The refresh of a session as the web layer sees it (session-endpoints-cookie-and-csrf design.md
 * decision 14): the rotation of a refresh token, translated into the two answers the endpoint has.
 * The session was refreshed, and the {@link SessionGrant} carries the new access token and the
 * refresh token to put in the cookie; or it was not, and nothing says why.
 *
 * <p><b>The reason of a rejection stops here.</b> {@link RotateRefreshToken} knows six of them and
 * writes each in the trail, but the answer to the client is one: an endpoint that told an unknown
 * token from a revoked family, or a reuse from an expiry, would be an oracle for whoever holds a
 * stolen token. The web layer receives {@link Rejected} and cannot tell them apart. It owns neither
 * a domain type nor a token type, so it depends on this class and on nothing under {@code domain}.
 *
 * <p>A grace replay and a fresh rotation are both {@link Refreshed}; the kind is reported so that a
 * test, and a log line without secrets, can tell them apart, and the cookies are the same either way.
 */
public final class RefreshSession {

    private final RotateRefreshToken rotation;

    public RefreshSession(RotateRefreshToken rotation) {
        this.rotation = Objects.requireNonNull(rotation, "rotation");
    }

    public RefreshResult execute(RotateRefreshTokenCommand command) {
        Objects.requireNonNull(command, "command");
        return switch (rotation.execute(command)) {
            case RefreshOutcome.Rotated rotated -> new Refreshed(
                    new SessionGrant(rotated.session().accessToken().compact(),
                            rotated.session().accessExpiresAt(),
                            rotated.session().refreshToken().value(), rotated.sessionExpiresAt(),
                            rotated.idleExpiresAt()),
                    rotated.kind());
            case RefreshOutcome.Rejected rejected -> new Rejected();
        };
    }

    /** What the refresh came to. */
    public sealed interface RefreshResult permits Refreshed, Rejected {
    }

    /**
     * The session was refreshed.
     *
     * @param session the new access token and the refresh token that replaces the one presented
     * @param kind whether a new token was issued or the one already issued was handed out again
     */
    public record Refreshed(SessionGrant session, RotationKind kind) implements RefreshResult {

        public Refreshed {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The token was not accepted, for a reason the caller is not told. */
    public record Rejected() implements RefreshResult {
    }
}
