package com.confia.charges.application;

import java.time.Instant;
import java.util.UUID;

/** A row of the posting map as the layers outside the domain read it; {@code conceptCategory} is null for a purpose that has none. */
public record PostingAccountView(UUID id, String purpose, String conceptCategory, UUID ledgerAccountId, long version,
        Instant updatedAt) {

    static PostingAccountView of(PostingAccountRow row) {
        return new PostingAccountView(row.id(), row.key().purpose().name(), row.key().conceptCategory(),
                row.ledgerAccountId(), row.version(), row.updatedAt());
    }
}
