package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * The ledger refused to reverse the transaction of the charge (the account of the student is under review, the transaction
 * cannot be reversed, or it was reversed outside a void). Nothing was written. Published to clients as the Problem Details
 * code {@code charge-not-voidable}.
 */
public final class ChargeNotVoidableException extends DomainException {

    public static final String CODE = "charge-not-voidable";

    public ChargeNotVoidableException() {
        super(CODE, "the ledger refused to reverse the transaction of the charge");
    }
}
