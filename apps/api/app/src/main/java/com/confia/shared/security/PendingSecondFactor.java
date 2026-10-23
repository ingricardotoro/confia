package com.confia.shared.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The holder of a restricted second-factor token on a route of the restricted class
 * (session-endpoints-cookie-and-csrf design.md, decision 6). It is a type of its own and is bound in a
 * {@link ScopedValue} of its own, never in {@link AuthenticatedActor#CURRENT}: whoever reads the
 * session actor, such as the institution provider or the session-validity check, never sees the
 * holder of a restricted token and cannot mistake it for a session.
 *
 * <p>It holds identifiers and a purpose, never the token. {@code toString} leaves out the token
 * identifier, because a {@code jti} is never written to a log.
 *
 * @param accountId the staff account ({@code sub}), an opaque identifier
 * @param institutionId the institution ({@code tenant})
 * @param tokenId the identifier of the token ({@code jti})
 * @param purpose what the token may be used for
 * @param issuedAt when the token was issued ({@code iat})
 * @param expiresAt when the token stops being valid ({@code exp})
 */
public record PendingSecondFactor(UUID accountId, UUID institutionId, UUID tokenId,
        SecondFactorPurpose purpose, Instant issuedAt, Instant expiresAt) {

    private static final String STAFF = "staff";

    /** Bound by the authentication filter around the rest of the chain of a restricted route. */
    public static final ScopedValue<PendingSecondFactor> CURRENT = ScopedValue.newInstance();

    public PendingSecondFactor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** The holder of the restricted token of the request this thread is serving, if any. */
    public static Optional<PendingSecondFactor> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** The row-level-security parameters of this account for the given request. */
    public SecurityContext securityContext(String requestId) {
        return new SecurityContext(accountId.toString(), STAFF, institutionId.toString(), requestId);
    }

    @Override
    public String toString() {
        return "PendingSecondFactor[accountId=" + accountId + ", institutionId=" + institutionId
                + ", purpose=" + purpose + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
