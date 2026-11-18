package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code unbalanced-transaction}. */
public final class UnbalancedTransactionException extends DomainException {

    public static final String CODE = "unbalanced-transaction";

    public UnbalancedTransactionException(String detail) {
        super(CODE, detail);
    }
}
