package com.confia.identity.domain;

import java.util.Objects;

/**
 * The Argon2id PHC-formatted hash stored in {@code identity_mfa_recovery_code.code_hash}
 * (column-encryption-and-mfa-totp design.md, decision 3: {@code CHECK (code_hash LIKE
 * '$argon2id$%')}; decision 9). This is the "mismo trato que {@code StoredPasswordHash}" design.md
 * decision 9's own table names for this class: same redaction discipline, applied to a value that
 * is already a hash rather than clear text — a hash is still never printed, following {@link
 * StoredPasswordHash}'s exact precedent, a plain final class with an explicit override, never a
 * record.
 *
 * <p>The constructor mirrors {@code identity_mfa_recovery_code}'s own {@code CHECK (code_hash LIKE
 * '$argon2id$%')} (column-encryption-and-mfa-totp design.md, decision 3), so a malformed value
 * fails in the application before it ever reaches a statement — the same reasoning {@link
 * StoredPasswordHash} already applies to its own column's {@code CHECK}.
 */
public final class StoredRecoveryCodeHash {

    private static final String PHC_PREFIX = "$argon2id$";

    private final String value;

    public StoredRecoveryCodeHash(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith(PHC_PREFIX)) {
            throw new IllegalArgumentException(
                    "a stored recovery code hash must start with " + PHC_PREFIX);
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredRecoveryCodeHash that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: {@code value} is the Argon2id hash itself. */
    @Override
    public String toString() {
        return "StoredRecoveryCodeHash[REDACTED]";
    }
}
