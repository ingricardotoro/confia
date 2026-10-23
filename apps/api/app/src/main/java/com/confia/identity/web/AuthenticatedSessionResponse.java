package com.confia.identity.web;

import com.confia.identity.application.SessionGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Objects;

/**
 * The answer of a login that started a session (session-endpoints-cookie-and-csrf design.md
 * decision 11): the access token and when each end of the session comes, and nothing personal and
 * no identifier of the session. The refresh token does not travel here: it is in the cookie.
 *
 * <p>{@code toString()} redacts the access token, which a record would print.
 *
 * @param status always {@code authenticated}
 * @param accessToken the compact access token, for the {@code Authorization} header
 * @param accessTokenExpiresAt when the access token stops being valid
 * @param sessionExpiresAt the absolute end of the session, whatever its use
 * @param idleExpiresAt when the session becomes idle if it is not used again
 */
public record AuthenticatedSessionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = "authenticated",
                description = "Always authenticated: a session was started")
        String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "password",
                description = "The access token, for the Authorization header")
        String accessToken,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "When the access token stops being valid")
        Instant accessTokenExpiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The absolute end of the session, whatever its use")
        Instant sessionExpiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "When the session becomes idle if it is not used again")
        Instant idleExpiresAt) implements LoginResponse {

    static final String STATUS = "authenticated";

    public AuthenticatedSessionResponse {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt");
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
    }

    /** The answer for the session the use case granted. */
    static AuthenticatedSessionResponse of(SessionGrant grant) {
        return new AuthenticatedSessionResponse(STATUS, grant.accessToken(),
                grant.accessTokenExpiresAt(), grant.sessionExpiresAt(), grant.idleExpiresAt());
    }

    @Override
    public String toString() {
        return "AuthenticatedSessionResponse[status=" + status + ", accessToken=REDACTED]";
    }
}
