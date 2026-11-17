package com.confia.ledger.application.api;

import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code ledger} about accounts of the chart of an institution (for
 * example to prove that the accounts of a posting map exist, are active and are of the right type). Scoped to the institution
 * given: an account of another institution is never found.
 */
public interface LedgerAccountDirectory {

    /** The accounts with these identifiers, by identifier, in one query; unknown identifiers are absent. */
    Map<UUID, LedgerAccountRef> find(InstitutionId institutionId, Collection<UUID> accountIds);

    /** An account as another module may see it: its type is {@code ASSET}, {@code LIABILITY}, {@code EQUITY}, {@code INCOME} or {@code EXPENSE}. */
    record LedgerAccountRef(UUID id, String code, String name, String type, boolean holdsStudentBalances,
            boolean active) {

        public LedgerAccountRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
        }
    }
}
