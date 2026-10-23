package com.confia.identity.domain;

import java.util.Objects;

/**
 * The two mutable fields of one {@code identity_mfa_totp_credential} row a reader needs
 * (column-encryption-and-mfa-totp design.md, decision 3, §4.2): the secret, still in its
 * encrypted, stored form ({@code v1:<id_dek>:<iv_b64>:<ciphertext_b64>:<tag_b64>}, decision 5 —
 * decryption is the caller's job through {@link com.confia.shared.crypto.ColumnEncryptionService},
 * never this class's), and the last TOTP counter this account has already accepted.
 *
 * <p>A credential is verified once it accepted a code ({@link #isVerified()}).
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

    /**
     * Whether the credential has accepted at least one TOTP code (session-endpoints-cookie-and-csrf
     * design.md, decision 13). A credential that was enrolled and never verified keeps the
     * sentinel {@code -1}; the first accepted counter, even the first possible one, is {@code
     * 0} or more. "MFA active" means a required second factor and a verified credential, so a
     * secret nobody proved to have is not a second factor.
     */
    public boolean isVerified() {
        return lastAcceptedCounter >= 0;
    }
}
