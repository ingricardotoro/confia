package com.confia.shared.crypto;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a data-encryption key within its institution, part of {@code
 * shared_data_encryption_key}'s composite primary key {@code (institution_id, id)}
 * (column-encryption-and-mfa-totp design.md, decisions 1 and 3). Not a secret itself — it is the
 * {@code <id_dek>} component of the stored five-part format ({@code
 * com.confia.kernel.EncryptedColumnValue}) and is expected to travel in the clear next to the
 * ciphertext it identifies.
 *
 * <p>A {@code null} value is a programming error (ADR-0019, point 6), not a business condition:
 * the compact constructor throws {@link NullPointerException}, never a {@code DomainException}
 * subclass, following {@code com.confia.kernel.InstitutionId}'s own precedent.
 */
public record DataEncryptionKeyId(UUID value) {

    public DataEncryptionKeyId {
        Objects.requireNonNull(value, "value");
    }
}
