package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored session family as the rotation reads it, after taking its lock (session-tokens-and-web-layer
 * design.md decisions 8 and 10): the facts {@link RotationDecision} needs and nothing else. It is not
 * {@link RefreshTokenFamily}, which is a family as the issuance writes it, born unrevoked. The
 * fingerprint prints redacted and {@link #toString()} leaves the address out, because no log may hold
 * the address of a client.
 *
 * @param secondFactorVerified decides the {@code amr} of the access token the rotation signs
 * @param lastUsedAt when the family was last rotated, or created
 * @param revokedAt when the family was revoked, or {@code null} while it is not
 * @param creationFingerprint the fingerprint of the agent that opened the family; that of no text when
 *     it was opened with no agent (session-endpoints-cookie-and-csrf design.md decision 14)
 * @param ipFirstSeen the address the family was opened from, as the database writes it, or {@code
 *     null} when it was opened with none
 */
public record RefreshTokenFamilyState(UUID id, StaffAccountId accountId,
        boolean secondFactorVerified, Instant lastUsedAt, Instant absoluteExpiresAt,
        Instant revokedAt, UserAgentFingerprint creationFingerprint, String ipFirstSeen) {

    public RefreshTokenFamilyState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        Objects.requireNonNull(creationFingerprint, "creationFingerprint");
    }

    /** A family with no recorded origin: the fingerprint of no agent, and no address. */
    public RefreshTokenFamilyState(UUID id, StaffAccountId accountId, boolean secondFactorVerified,
            Instant lastUsedAt, Instant absoluteExpiresAt, Instant revokedAt) {
        this(id, accountId, secondFactorVerified, lastUsedAt, absoluteExpiresAt, revokedAt,
                UserAgentFingerprint.NO_AGENT, null);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Without the address of the client. */
    @Override
    public String toString() {
        return "RefreshTokenFamilyState[id=" + id + ", accountId=" + accountId
                + ", secondFactorVerified=" + secondFactorVerified + ", lastUsedAt=" + lastUsedAt
                + ", absoluteExpiresAt=" + absoluteExpiresAt + ", revokedAt=" + revokedAt + "]";
    }
}
