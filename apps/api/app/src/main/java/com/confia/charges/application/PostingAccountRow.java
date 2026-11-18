package com.confia.charges.application;

import com.confia.charges.domain.PostingAccountKey;
import java.time.Instant;
import java.util.UUID;

/** One stored row of the posting map: its key, the account it points at, and the version and instant of its last write. */
public record PostingAccountRow(UUID id, PostingAccountKey key, UUID ledgerAccountId, long version, Instant updatedAt) {
}
