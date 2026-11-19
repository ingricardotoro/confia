package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** A close with a difference carries a reason of 20 to 500 characters (INV-23). Published to clients as the Problem Details code {@code cash-difference-reason-required}. */
public final class CashDifferenceReasonRequiredException extends DomainException {

    public static final String CODE = "cash-difference-reason-required";

    public CashDifferenceReasonRequiredException() {
        super(CODE, "a difference needs a reason of 20 to 500 characters");
    }
}
