package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The cashier already has an open session (INV-22). Published to clients as the Problem Details code {@code cash-session-already-open}. */
public final class CashSessionAlreadyOpenException extends DomainException {

    public static final String CODE = "cash-session-already-open";

    public CashSessionAlreadyOpenException() {
        super(CODE, "the cashier already has an open cash session");
    }
}
