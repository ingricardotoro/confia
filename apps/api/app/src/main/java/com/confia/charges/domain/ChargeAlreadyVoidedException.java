package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * The charge was voided before and a charge is voided at most once. Published to clients as the Problem Details code
 * {@code charge-already-voided}.
 */
public final class ChargeAlreadyVoidedException extends DomainException {

    public static final String CODE = "charge-already-voided";

    public ChargeAlreadyVoidedException() {
        super(CODE, "the charge was already voided");
    }
}
