package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored refresh token as the rotation reads it (session-tokens-and-web-layer design.md decision
 * 10): its window, whether and by whom it was consumed, and two facts about its successor that the
 * grace window needs. It is not {@link RefreshTokenRow}, which is a token as the issuance writes it.
 * It holds neither the hash nor the encrypted successor, only whether the latter is still present,
 * and the fingerprint prints redacted, so a plain record is safe to print.
 *
 * <p>Consumption is all or nothing, as the {@code CHECK} of {@code V8} makes it: the instant and the
 * fingerprint of the presenter are both present or both absent, and an unconsumed token has neither
 * an encrypted successor nor a consumed one.
 *
 * @param consumedAt when the token was consumed, or {@code null} while it is open
 * @param consumedUserAgentFingerprint who presented it when it was consumed, or {@code null}
 * @param successorCiphertextPresent whether the encrypted successor is still in the row
 * @param successorConsumed whether the token that replaced this one has itself been consumed
 */
public record RefreshTokenState(UUID id, UUID familyId, Instant issuedAt, Instant expiresAt,
        Instant consumedAt, UserAgentFingerprint consumedUserAgentFingerprint,
        boolean successorCiphertextPresent, boolean successorConsumed) {

    public RefreshTokenState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if ((consumedAt == null) != (consumedUserAgentFingerprint == null)) {
            throw new IllegalArgumentException("a consumption has both its instant and the "
                    + "fingerprint of who presented the token, or neither");
        }
        if (consumedAt == null && (successorCiphertextPresent || successorConsumed)) {
            throw new IllegalArgumentException("a token that was not consumed has no successor");
        }
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
