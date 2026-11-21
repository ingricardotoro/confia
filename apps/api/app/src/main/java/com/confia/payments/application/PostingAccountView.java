package com.confia.payments.application;

import com.confia.payments.application.PostingAccountRepository.PostingAccountRow;
import java.time.Instant;
import java.util.UUID;

/** A row of the payments posting map as the layers outside the domain read it. */
public record PostingAccountView(UUID id, String purpose, UUID ledgerAccountId, long version, Instant updatedAt) {

    static PostingAccountView of(PostingAccountRow row) {
        return new PostingAccountView(row.id(), row.purpose().name(), row.ledgerAccountId(), row.version(),
                row.updatedAt());
    }
}
