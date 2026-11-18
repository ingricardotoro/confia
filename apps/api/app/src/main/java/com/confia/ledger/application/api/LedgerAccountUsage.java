package com.confia.ledger.application.api;

import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Port that other modules IMPLEMENT to tell {@code ledger} that an account of the chart is in use by them (today the posting
 * map of {@code charges}). The ledger asks every one of them before it deactivates an account, so a module that points at an
 * account never finds it switched off under it. It is an inversion of the dependency: {@code ledger} knows no module, the
 * modules know this interface, and the module graph has no cycle.
 */
public interface LedgerAccountUsage {

    /** Whether this module points at the account in the given institution. Read inside the transaction of the caller. */
    boolean isReferenced(InstitutionId institutionId, UUID accountId);
}
