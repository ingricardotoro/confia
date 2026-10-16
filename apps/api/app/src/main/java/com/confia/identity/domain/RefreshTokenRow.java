package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A new refresh token as the issuance writes it (session-tokens-and-web-layer design.md decision 8):
 * its identity, its family, the hash of the delivered text and its window. The clear text is not part
 * of it, and the hash prints redacted, so a plain record is safe to print.
 */
public record RefreshTokenRow(UUID id, UUID familyId, RefreshTokenHash hash, Instant issuedAt,
        Instant expiresAt) {

    public RefreshTokenRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("a refresh token expires after it is issued");
        }
    }
}
