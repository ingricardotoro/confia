package com.confia.shared.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The staff member a request was authenticated as, built from a verified access token and nothing else
 * (session-tokens-and-web-layer design.md, decision 5). The authentication filter binds one for the
 * whole of a request in {@link #CURRENT}, exactly as the request filter binds a {@link RequestOrigin}:
 * a {@link ScopedValue} is immutable, exists only while the filter's call runs and cannot be seen from
 * another request even when the thread is reused. It is not inherited by work handed to a plain
 * executor, so code must not rely on it outside the request thread.
 *
 * <p>It holds identifiers, never a personal datum and never the token. {@code toString} leaves out the
 * token identifier as well, because a {@code jti} is never written to a log.
 *
 * @param accountId the staff account ({@code sub}), an opaque identifier
 * @param institutionId the institution the session belongs to ({@code tenant})
 * @param sessionId the refresh-token family the access token was issued for ({@code sid})
 * @param tokenId the identifier of this token ({@code jti})
 * @param methods how the staff member authenticated ({@code amr}); never empty
 * @param issuedAt when the token was issued ({@code iat})
 * @param expiresAt when the token stops being valid ({@code exp})
 */
public record AuthenticatedActor(UUID accountId, UUID institutionId, UUID sessionId, UUID tokenId,
        Set<AuthenticationMethod> methods, Instant issuedAt, Instant expiresAt) {

    private static final String STAFF = "staff";

    /** Bound by the authentication filter around the rest of the filter chain; unbound elsewhere. */
    public static final ScopedValue<AuthenticatedActor> CURRENT = ScopedValue.newInstance();

    public AuthenticatedActor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        methods = Set.copyOf(Objects.requireNonNull(methods, "methods"));
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("an authenticated actor has at least one method");
        }
    }

    /** The actor of the request this thread is serving, empty outside an authenticated request. */
    public static Optional<AuthenticatedActor> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** The row-level-security parameters of this actor for the given request. */
    public SecurityContext securityContext(String requestId) {
        return new SecurityContext(accountId.toString(), STAFF, institutionId.toString(), requestId);
    }

    @Override
    public String toString() {
        return "AuthenticatedActor[accountId=" + accountId + ", institutionId=" + institutionId
                + ", sessionId=" + sessionId + ", methods=" + methods + ", issuedAt=" + issuedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
