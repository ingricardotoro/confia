package com.confia.shared.crypto;

import com.confia.kernel.InstitutionId;

/**
 * The single port over {@code shared_data_encryption_key} (column-encryption-and-mfa-totp
 * design.md, decision 2). {@link com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository}
 * is its single jOOQ adapter.
 */
public interface DataEncryptionKeyRepository {

    /**
     * Returns the institution's active data-encryption key, creating it the first time it is
     * needed (design.md, decision 4). Safe under concurrency: two callers racing to create the
     * first DEK of an institution that has none yet both resolve to the very same winning key,
     * never two active rows and never an error.
     */
    DataEncryptionKeyMaterial findActiveOrCreate(InstitutionId institutionId);

    /**
     * Returns exactly the data-encryption key {@code id} names, active or retired — used to
     * decrypt a value stored under a key that may since have been retired (design.md, decision 5,
     * "Cómo se elige la DEK").
     */
    DataEncryptionKeyMaterial findById(InstitutionId institutionId, DataEncryptionKeyId id);
}
