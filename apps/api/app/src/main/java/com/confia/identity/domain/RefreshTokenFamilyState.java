package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored session family as the rotation reads it, after taking its lock (session-tokens-and-web-layer
 * design.md decisions 8 and 10): the facts {@link RotationDecision} needs and nothing else. It is not
 * {@link RefreshTokenFamily}, which is a family as the issuance writes it, born unrevoked. It carries
 * no secret, so a plain record is safe to print.
 *
 * @param secondFactorVerified decides the {@code amr} of the access token the rotation signs
 * @param lastUsedAt when the family was last rotated, or created
 * @param revokedAt when the family was revoked, or {@code null} while it is not
 */
public record RefreshTokenFamilyState(UUID id, StaffAccountId accountId,
        boolean secondFactorVerified, Instant lastUsedAt, Instant absoluteExpiresAt,
        Instant revokedAt) {

    public RefreshTokenFamilyState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
