package com.confia.identity.domain;

import java.util.Objects;

/**
 * The two mutable fields of one {@code identity_mfa_totp_credential} row a reader needs
 * (column-encryption-and-mfa-totp design.md, decision 3, §4.2): the secret, still in its
 * encrypted, stored form ({@code v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>}, decision 5 —
 * decryption is the caller's job through {@link com.confia.shared.crypto.ColumnEncryptionService},
 * never this class's), and the last TOTP counter this account has already accepted.
 *
 * <p>Not a secret in the sense CLAUDE.md regla 11 or design.md decision 9 name: {@code
 * encryptedSecret} is already ciphertext, safe to print, so this stays a plain record — unlike
 * {@link PlainTotpSecret}, which carries the same value once decrypted.
 */
public record TotpCredential(StaffAccountId accountId, String encryptedSecret,
        long lastAcceptedCounter) {

    public TotpCredential {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(encryptedSecret, "encryptedSecret");
    }
}
