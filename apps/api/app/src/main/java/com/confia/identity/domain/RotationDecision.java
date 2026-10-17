package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a presented refresh token leads to (session-tokens-and-web-layer design.md decisions 10 and
 * 11): a pure function of the family, the token, who presents it and the instant. It reads no clock
 * and no database, so every border is tested at the exact instant it flips.
 *
 * <p><b>The order of evaluation is fixed</b>, and each step hides the ones after it: a revoked family
 * is rejected whatever its token; then a family that ended (absolutely or by inactivity) is rejected,
 * so a consumed token presented to an expired family is an expiry and never a reuse; then a consumed
 * token is a grace replay or a reuse, which is evaluated before the token's own expiry; and only an
 * open token reaches its own expiry and, within it, a normal rotation.
 *
 * <p>{@link #GRACE_REPLAY} never happens ten seconds or more after the consumption: the decryption
 * of the successor is only ever asked for inside the window.
 */
public enum RotationDecision {

    /** Order 1. The family is revoked; nothing changes. */
    REJECT_FAMILY_REVOKED(RefreshRejection.FAMILY_REVOKED),
    /** Order 2. The family ended or is idle; the encrypted successors of the family are emptied. */
    REJECT_FAMILY_EXPIRED(RefreshRejection.FAMILY_EXPIRED),
    /** Order 3. A retry from the same device inside the window; it returns the same successor. */
    GRACE_REPLAY(null),
    /** Order 4. A consumed token in any other case; the family is revoked. */
    REUSE_DETECTED(RefreshRejection.REUSE_DETECTED),
    /** Order 5. The token itself expired while its family was alive; nothing changes. */
    REJECT_TOKEN_EXPIRED(RefreshRejection.TOKEN_EXPIRED),
    /** Order 6. A normal rotation. */
    ROTATE(null);

    private final RefreshRejection rejection;

    RotationDecision(RefreshRejection rejection) {
        this.rejection = rejection;
    }

    /** The reason the caller reports, or empty when the decision lets the token through. */
    public Optional<RefreshRejection> rejection() {
        return Optional.ofNullable(rejection);
    }

    /**
     * @param presenter the fingerprint of the agent that presents the token now
     */
    public static RotationDecision decide(RefreshTokenFamilyState family, RefreshTokenState token,
            UserAgentFingerprint presenter, Instant now, SessionLifetimePolicy policy) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(presenter, "presenter");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(policy, "policy");

        if (family.isRevoked()) {
            return REJECT_FAMILY_REVOKED;
        }
        if (policy.isAbsolutelyExpired(family.absoluteExpiresAt(), now)
                || policy.isIdle(family.lastUsedAt(), now)) {
            return REJECT_FAMILY_EXPIRED;
        }
        if (token.isConsumed()) {
            boolean replayable = policy.isWithinGraceWindow(token.consumedAt(), now)
                    && presenter.equals(token.consumedUserAgentFingerprint())
                    && token.successorCiphertextPresent() && !token.successorConsumed();
            return replayable ? GRACE_REPLAY : REUSE_DETECTED;
        }
        if (!now.isBefore(token.expiresAt())) {
            return REJECT_TOKEN_EXPIRED;
        }
        return ROTATE;
    }
}
