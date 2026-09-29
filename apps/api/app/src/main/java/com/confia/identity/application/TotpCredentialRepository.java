package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.InstitutionId;
import java.util.Optional;

/**
 * Reads and writes {@code identity_mfa_totp_credential}'s row (column-encryption-and-mfa-totp
 * design.md, decision 3, §4.2). {@link com.confia.identity.infrastructure.JooqTotpCredentialRepository}
 * is its single jOOQ adapter.
 */
public interface TotpCredentialRepository {

    /**
     * Inserts the row for a newly enrolled account, {@code encryptedSecret} already encrypted by
     * {@link com.confia.shared.crypto.ColumnEncryptionService} — this port never encrypts or
     * decrypts anything itself (design.md, decision 2, point 4). {@code last_accepted_counter}
     * starts at its schema default of {@code -1} (design.md, decision 3, point 2).
     */
    void insert(InstitutionId institutionId, StaffAccountId accountId, String encryptedSecret);

    Optional<TotpCredential> findByAccountId(InstitutionId institutionId, StaffAccountId accountId);

    /**
     * The conditional counter update design.md decision 7 fixes exactly: {@code UPDATE ... SET
     * last_accepted_counter = candidateCounter WHERE ... AND last_accepted_counter < candidateCounter
     * RETURNING account_id}. Returns {@code true} when the update affected the row (this candidate
     * counter is now the account's accepted one), {@code false} when it affected zero rows — the
     * signal that a concurrent verification already accepted a counter greater than or equal to
     * this one, the same defeat this candidate must be treated as a rejection for, even if its own
     * code was mathematically valid.
     */
    boolean acceptCounter(InstitutionId institutionId, StaffAccountId accountId,
            long candidateCounter);
}
