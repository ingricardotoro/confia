package com.confia.ledger.application;

import com.confia.shared.security.SecurityContext;
import java.util.Objects;
import java.util.UUID;

/** Who writes to the ledger: a staff member or the {@code system} actor of a job, in one institution. */
public record LedgerActor(UUID institutionId, UUID actorId, String kind, UUID requestId) {

    public LedgerActor {
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
    }

    SecurityContext securityContext() {
        return new SecurityContext(actorId.toString(), kind, institutionId.toString(), requestId.toString());
    }
}
