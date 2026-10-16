package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A new session family as the issuance writes it (session-tokens-and-web-layer design.md decision
 * 8): the account, whether the second factor was completed (which decides the {@code amr} of its
 * access tokens), the fingerprint of the agent and the first address of the client, and its window.
 * A family is born unrevoked; revocation is a later change of state that this record does not model.
 * It carries no secret, so a plain record is safe to print.
 *
 * @param ipFirstSeen the canonical text of the first address, or {@code null} when the issuance had
 *     none; kept in clear text by the owner's decision (docs/08, section 4.1)
 */
public record RefreshTokenFamily(UUID id, StaffAccountId accountId, boolean secondFactorVerified,
        UserAgentFingerprint userAgentFingerprint, String ipFirstSeen, Instant createdAt,
        Instant lastUsedAt, Instant absoluteExpiresAt) {

    public RefreshTokenFamily {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(userAgentFingerprint, "userAgentFingerprint");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastUsedAt, "lastUsedAt");
        Objects.requireNonNull(absoluteExpiresAt, "absoluteExpiresAt");
        if (!absoluteExpiresAt.isAfter(createdAt) || lastUsedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("a session family ends after it is created and is "
                    + "never used before it exists");
        }
    }
}
