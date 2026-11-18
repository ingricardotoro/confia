package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code transaction-already-reversed}. */
public final class TransactionAlreadyReversedException extends DomainException {

    public static final String CODE = "transaction-already-reversed";

    public TransactionAlreadyReversedException() {
        super(CODE, "the transaction was already reversed");
    }
}
