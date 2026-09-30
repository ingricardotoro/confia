package com.confia.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One stored password-reset token as the reset reads it (password-recovery-token design.md
 * decisions 4 and 5). It carries no secret, not even the hash it was found by, so a plain record is
 * safe to print. {@code consumedAt} and {@code supersededAt} are {@code null} while the token is
 * open; {@code V7}'s {@code identity_password_reset_token_final_chk} keeps them from both being set.
 */
public record PasswordResetTokenRow(UUID id, StaffAccountId accountId, Instant issuedAt,
        Instant expiresAt, Instant consumedAt, Instant supersededAt) {

    public PasswordResetTokenRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
