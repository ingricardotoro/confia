package com.confia.shared.security.token;

import com.confia.shared.security.AuthenticatedActor;
import java.time.Clock;
import java.util.Objects;

/**
 * Verifies the administrative access token (session-tokens-and-web-layer design.md, decisions 2 and
 * 4): the codec first, whose six steps decide the signature, and then the claim rules of the access
 * token. {@link #verifyAccess} requires the audience {@code confia-admin} and exactly the nine claims
 * of an access token, so any token of another kind fails it by its audience or its claim list alone.
 *
 * <p>The only thing that ever leaves the method is the verified actor or a {@link
 * TokenRejectedException} whose reason carries no data: not for a {@code null}, not for ten thousand
 * characters, not for a megabyte. The verifier writes nothing to any log. There is no tolerance on
 * {@code exp}; the clock is injected.
 */
public final class AccessTokenVerifier {

    private final CompactJws jws;
    private final Clock clock;

    public AccessTokenVerifier(CompactJws jws, Clock clock) {
        this.jws = Objects.requireNonNull(jws, "jws");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The actor an access token describes.
     *
     * @throws TokenRejectedException for any token that is not a valid, current access token
     */
    public AuthenticatedActor verifyAccess(String compact) {
        return AccessTokenClaims.read(jws.verify(compact).claims(), clock.instant());
    }
}
