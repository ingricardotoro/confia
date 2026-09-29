package com.confia.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One unused row of {@code identity_mfa_recovery_code} a reader needs to attempt a match
 * (column-encryption-and-mfa-totp design.md, §4.3): the row's own identifier, so a match can be
 * marked used by exactly this row, and the stored hash to compare against. {@code hash} is
 * already a {@link StoredRecoveryCodeHash} — a plain record is safe here because that type's own
 * {@code toString()} is already redacted, the same reasoning {@code TotpCredential} already
 * applies to a field that is already safe to print.
 */
public record RecoveryCodeRow(UUID id, StoredRecoveryCodeHash hash) {

    public RecoveryCodeRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(hash, "hash");
    }
}
