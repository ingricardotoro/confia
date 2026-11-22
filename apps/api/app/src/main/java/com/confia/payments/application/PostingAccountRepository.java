package com.confia.payments.application;

import com.confia.payments.domain.PostingPurpose;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the map of posting accounts of payments (the cash and the bank accounts): DATA, never code. A row is inserted the
 * first time its purpose is set and its account is replaced afterwards, with the version the editor read; no row is ever deleted,
 * so a map can be corrected but never silently emptied.
 */
public interface PostingAccountRepository {

    /** The rows of the map, ordered by purpose. */
    List<PostingAccountRow> listRows(InstitutionId institution);

    /** The row of the purpose, locked {@code FOR UPDATE}. */
    Optional<PostingAccountRow> findRow(InstitutionId institution, PostingPurpose purpose);

    /** Inserts the row at version 0; a row that already exists for the purpose is a stale-version error. */
    PostingAccountRow insert(InstitutionId institution, UUID id, PostingPurpose purpose, UUID ledgerAccountId, Instant now);

    /** Points the row at another account and moves its version, if the stored version is {@code expectedVersion}. */
    PostingAccountRow replaceAccount(InstitutionId institution, PostingAccountRow row, UUID ledgerAccountId,
            long expectedVersion, Instant now);

    /** One stored row: its purpose, the account it points at, and the version and instant of its last write. */
    record PostingAccountRow(UUID id, PostingPurpose purpose, UUID ledgerAccountId, long version, Instant updatedAt) {
    }
}
