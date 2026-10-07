package com.confia.shared.security.token;

import com.confia.shared.security.AuthenticationMethod;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Issues the two kinds of administrative token (session-tokens-and-web-layer design.md, decision 4):
 * the access token, ten minutes of life for a session, and the restricted MFA token, five minutes for
 * completing the second factor. Both are signed by the codec with the current key, carry a random
 * {@code jti}, and take their issue time from the injected clock truncated to whole seconds.
 *
 * <p>It holds the codec and a clock and nothing else. In particular it writes to no table: issuing a
 * restricted token creates no refresh-token family and issuing an access token does not either, because
 * the family is the business of the session use case, which asks for a token once it has one.
 */
public final class AccessTokenIssuer {

    private final CompactJws jws;
    private final Clock clock;

    public AccessTokenIssuer(CompactJws jws, Clock clock) {
        this.jws = Objects.requireNonNull(jws, "jws");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * An access token for a session.
     *
     * @param accountId the staff account
     * @param institutionId the institution of the session
     * @param sessionId the refresh-token family the token belongs to
     * @param methods how the staff member authenticated; must contain the password, and contains the
     *     one-time password only when the second factor was completed
     * @throws IllegalArgumentException when the methods do not contain the password
     */
    public AccessToken issueAccess(UUID accountId, UUID institutionId, UUID sessionId,
            Set<AuthenticationMethod> methods) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(methods, "methods");
        Instant issuedAt = now();
        byte[] payload = AccessTokenClaims.serialize(accountId, institutionId, sessionId,
                UUID.randomUUID(), methods, issuedAt);
        return new AccessToken(jws.sign(payload), issuedAt.plus(AccessTokenClaims.LIFETIME));
    }

    /** A restricted token for completing the second factor: no session, five minutes. */
    public AccessToken issueMfa(UUID accountId, UUID institutionId, MfaPurpose purpose) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(purpose, "purpose");
        Instant issuedAt = now();
        byte[] payload = MfaTokenClaims.serialize(accountId, institutionId, UUID.randomUUID(), purpose,
                issuedAt);
        return new AccessToken(jws.sign(payload), issuedAt.plus(MfaTokenClaims.LIFETIME));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
