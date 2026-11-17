package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code invalid-ledger-entry}. */
public final class InvalidLedgerEntryException extends DomainException {

    public static final String CODE = "invalid-ledger-entry";

    public InvalidLedgerEntryException(String detail) {
        super(CODE, detail);
    }
}
