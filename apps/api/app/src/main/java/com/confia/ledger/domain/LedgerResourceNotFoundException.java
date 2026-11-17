package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code resource-not-found}. */
public final class LedgerResourceNotFoundException extends DomainException {

    public static final String CODE = "resource-not-found";

    public LedgerResourceNotFoundException() {
        super(CODE, "the resource does not exist in the institution of the request");
    }
}
