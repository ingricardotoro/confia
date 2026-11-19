package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The session is closed or pending approval and accepts no movement and no second close (INV-21). Published to clients as the Problem Details code {@code cash-session-not-open}. */
public final class CashSessionNotOpenException extends DomainException {

    public static final String CODE = "cash-session-not-open";

    public CashSessionNotOpenException() {
        super(CODE, "the cash session is not open");
    }
}
