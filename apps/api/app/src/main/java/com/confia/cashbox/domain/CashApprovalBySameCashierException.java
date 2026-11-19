package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The approver is the cashier of the session (segregation of duties, docs/03-seguridad.md section 5.3). Published to clients as the Problem Details code {@code cash-approval-same-actor}. */
public final class CashApprovalBySameCashierException extends DomainException {

    public static final String CODE = "cash-approval-same-actor";

    public CashApprovalBySameCashierException() {
        super(CODE, "the approver of a close is never its cashier");
    }
}
