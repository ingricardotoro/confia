package com.confia.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the current-session read answers (session-tokens-and-web-layer design.md decision 13): the
 * session the access token belongs to and when each of its three ends comes, as plain values. It
 * carries no personal datum and no secret: the identifiers are opaque and the methods are the
 * {@code amr} values of the token and the permission codes of the account's role. The {@code web} layer copies it into its own explicit DTO.
 *
 * @param sessionId the refresh-token family of the token ({@code sid})
 * @param accountId the staff account ({@code sub}), an opaque identifier
 * @param institutionId the institution of the session ({@code tenant})
 * @param authenticationMethods the {@code amr} values, password first; never empty
 * @param accessTokenExpiresAt when the access token stops being valid ({@code exp})
 * @param sessionExpiresAt the absolute end of the session, whatever its use
 * @param idleExpiresAt when the session would become idle if it is not used again
 * @param permissions the codes ({@code module:verb}) of the permissions of the account's effective role,
 *     sorted; empty when it has no role. The same catalog the permission decider applies, read now
 */
public record CurrentSessionView(UUID sessionId, UUID accountId, UUID institutionId,
        List<String> authenticationMethods, Instant accessTokenExpiresAt, Instant sessionExpiresAt,
        Instant idleExpiresAt, List<String> permissions) {

    public CurrentSessionView {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        authenticationMethods = List.copyOf(
                Objects.requireNonNull(authenticationMethods, "authenticationMethods"));
        Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt");
        Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt");
        Objects.requireNonNull(idleExpiresAt, "idleExpiresAt");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
    }
}
