package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The session is not pending approval: it had no difference, or it was already approved. Published to clients as the Problem Details code {@code cash-session-not-awaiting-approval}. */
public final class CashSessionNotAwaitingApprovalException extends DomainException {

    public static final String CODE = "cash-session-not-awaiting-approval";

    public CashSessionNotAwaitingApprovalException() {
        super(CODE, "the cash session is not pending approval");
    }
}
