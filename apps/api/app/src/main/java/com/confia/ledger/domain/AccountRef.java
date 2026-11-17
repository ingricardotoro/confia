package com.confia.ledger.domain;

import java.util.UUID;

/** What the rules of an entry need to know of an account of the chart (INV-39). */
public record AccountRef(UUID id, boolean holdsStudentBalances, boolean active) {

    /** An entry carries a student if and only if its account holds student balances; a new fact needs an active account. */
    public void requireUsableBy(LedgerEntry entry, boolean mirrorOfAnOldFact) {
        if (holdsStudentBalances != (entry.studentId() != null)) {
            throw new InvalidLedgerEntryException(
                    "an entry carries a student if and only if its account holds student balances");
        }
        if (!active && !mirrorOfAnOldFact) {
            throw new InvalidLedgerEntryException("the account of the entry is not active");
        }
    }
}
