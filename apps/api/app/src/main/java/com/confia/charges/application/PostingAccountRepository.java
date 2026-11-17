package com.confia.charges.application;

import com.confia.charges.domain.PostingAccountKey;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the map of posting accounts of an institution: DATA, never code. A row is inserted the first time its purpose
 * and category are set and its account is replaced afterwards, with the version the editor read; no row is ever deleted, so a
 * map can be corrected but never silently emptied.
 */
public interface PostingAccountRepository {

    List<PostingAccountMap.Entry> list(InstitutionId institution);

    /** The rows of the map with their identifier and version, ordered by purpose and category. */
    List<PostingAccountRow> listRows(InstitutionId institution);

    Optional<PostingAccountRow> findRow(InstitutionId institution, PostingAccountKey key);

    /** Inserts the row at version 0; a row that already exists for the key is a stale-version error (somebody set it first). */
    PostingAccountRow insert(InstitutionId institution, UUID id, PostingAccountKey key, UUID ledgerAccountId, Instant now);

    /** Points the row at another account and moves its version, if the stored version is {@code expectedVersion}. */
    PostingAccountRow replaceAccount(InstitutionId institution, PostingAccountRow row, UUID ledgerAccountId,
            long expectedVersion, Instant now);
}
