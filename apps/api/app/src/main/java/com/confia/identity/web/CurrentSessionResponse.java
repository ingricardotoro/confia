package com.confia.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The body of {@code GET /api/v1/auth/sessions/current} (session-tokens-and-web-layer design.md
 * decision 13): a closed list of seven members and no personal datum, with dates in ISO 8601 in UTC.
 * It is the explicit output DTO of the route (CLAUDE.md, rule 10): plain values only, no type of
 * {@code domain} and none of jOOQ, so nothing the store adds to a family can reach the client.
 *
 * @param sessionId the identifier of the session, the {@code sid} of the token
 * @param accountId the opaque identifier of the staff account
 * @param institutionId the identifier of the institution of the session
 * @param authenticationMethods how the staff member authenticated, the {@code amr} values of the
 *     token ({@code pwd}, {@code otp})
 * @param accessTokenExpiresAt when the access token stops being valid
 * @param sessionExpiresAt the absolute end of the session, whatever its use
 * @param idleExpiresAt when the session becomes idle if it is not used again
 */
public record CurrentSessionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Identifier of the session")
        UUID sessionId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Opaque identifier of the staff account")
        UUID accountId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Identifier of the institution of the session")
        UUID institutionId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "How the staff member authenticated, as the amr values of the token")
        List<String> authenticationMethods,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "When the access token stops being valid")
        Instant accessTokenExpiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The absolute end of the session, whatever its use")
        Instant sessionExpiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "When the session becomes idle if it is not used again")
        Instant idleExpiresAt) {

    public CurrentSessionResponse {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        authenticationMethods = List.copyOf(
                Objects.requireNonNull(authenticationMethods, "authenticationMethods"));
        Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt");
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
    }
}
