package com.confia.ledger.application;

import com.confia.ledger.domain.LedgerAccount;
import java.time.Instant;
import java.util.UUID;

/** An account of the chart as the layers outside the domain read it: plain values, no domain type crosses the boundary. */
public record LedgerAccountView(UUID id, String code, String name, String type, boolean holdsStudentBalances,
        boolean active, long version, Instant createdAt, Instant updatedAt) {

    static LedgerAccountView of(LedgerAccount account) {
        return new LedgerAccountView(account.id(), account.code(), account.name(), account.type().name(),
                account.holdsStudentBalances(), account.active(), account.version(), account.createdAt(),
                account.updatedAt());
    }
}
