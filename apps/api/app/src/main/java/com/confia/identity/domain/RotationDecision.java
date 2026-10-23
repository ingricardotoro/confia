package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a presented refresh token leads to (session-tokens-and-web-layer design.md decisions 10 and
 * 11, widened by session-endpoints-cookie-and-csrf design.md decision 14): a pure function of the
 * family, the token, who presents it and the instant. It reads no clock and no database, so every
 * border is tested at the exact instant it flips.
 *
 * <p><b>The order of evaluation is fixed</b>, and each step hides the ones after it: a revoked family
 * is rejected whatever its token; then a family that ended (absolutely or by inactivity) is rejected,
 * so a consumed token presented to an expired family is an expiry and never a reuse; then a consumed
 * token that cannot be replayed is a reuse, which is evaluated before everything about the life of
 * the token itself; then the binding of the session, which only matters when the token would hand
 * credentials out (a rotation or a replay) or would answer on its own expiry; and only then the grace
 * replay, the expiry of the token and, within it, a normal rotation. A stolen token that was already
 * consumed is still a reuse, the more precise signal; the binding decides only what the token would
 * have given.
 *
 * <p>The binding is changed when <b>both</b> the agent and the address differ from the ones recorded
 * when the family was created. A family that recorded no agent (the fingerprint of no text) or no
 * address has nothing to compare against on that side, so that side never counts as changed. With the
 * binding unchanged, the result is the one the decision had before the binding existed.
 *
 * <p>{@link #GRACE_REPLAY} never happens ten seconds or more after the consumption: the decryption
 * of the successor is only ever asked for inside the window.
 */
public enum RotationDecision {

    /** Order 1. The family is revoked; nothing changes. */
    REJECT_FAMILY_REVOKED(RefreshRejection.FAMILY_REVOKED),
    /** Order 2. The family ended or is idle; the encrypted successors of the family are emptied. */
    REJECT_FAMILY_EXPIRED(RefreshRejection.FAMILY_EXPIRED),
    /** Order 3. A consumed token that cannot be replayed; the family is revoked. */
    REUSE_DETECTED(RefreshRejection.REUSE_DETECTED),
    /** Order 4. The agent and the address both changed; the family is revoked. */
    REJECT_BINDING_CHANGED(RefreshRejection.BINDING_CHANGED),
    /** Order 5. A retry from the same device inside the window; it returns the same successor. */
    GRACE_REPLAY(null),
    /** Order 6. The token itself expired while its family was alive; nothing changes. */
    REJECT_TOKEN_EXPIRED(RefreshRejection.TOKEN_EXPIRED),
    /** Order 7. A normal rotation. */
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
     * @param addressChanged whether the address of the presenter differs from the one recorded when
     *     the family was created; {@code false} when either of the two is unknown
     */
    public static RotationDecision decide(RefreshTokenFamilyState family, RefreshTokenState token,
            UserAgentFingerprint presenter, boolean addressChanged, Instant now,
            SessionLifetimePolicy policy) {
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
        boolean replayable = token.isConsumed()
                && policy.isWithinGraceWindow(token.consumedAt(), now)
                && presenter.equals(token.consumedUserAgentFingerprint())
                && token.successorCiphertextPresent() && !token.successorConsumed();
        if (token.isConsumed() && !replayable) {
            return REUSE_DETECTED;
        }
        if (addressChanged && agentChanged(family, presenter)) {
            return REJECT_BINDING_CHANGED;
        }
        if (replayable) {
            return GRACE_REPLAY;
        }
        if (!now.isBefore(token.expiresAt())) {
            return REJECT_TOKEN_EXPIRED;
        }
        return ROTATE;
    }

    /** A family that recorded no agent has none to differ from. */
    private static boolean agentChanged(RefreshTokenFamilyState family,
            UserAgentFingerprint presenter) {
        return !family.creationFingerprint().equals(UserAgentFingerprint.NO_AGENT)
                && !family.creationFingerprint().equals(presenter);
    }
}
