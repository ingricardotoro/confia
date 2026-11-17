package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code reversal-not-allowed}. */
public final class ReversalNotAllowedException extends DomainException {

    public static final String CODE = "reversal-not-allowed";

    public ReversalNotAllowedException(String detail) {
        super(CODE, detail);
    }
}
