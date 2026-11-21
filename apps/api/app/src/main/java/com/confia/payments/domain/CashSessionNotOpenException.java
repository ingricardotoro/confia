package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** Cash is collected only in the open session of the cashier who registers the payment (INV-20). Published to clients as the Problem Details code {@code cash-session-not-open}. */
public final class CashSessionNotOpenException extends DomainException {

    public static final String CODE = "cash-session-not-open";

    public CashSessionNotOpenException() {
        super(CODE, "the cashier has no open cash session");
    }
}
