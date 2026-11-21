package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * The charge has payments allocated to it, so it cannot be voided: the payment is reversed first (payments, cut 4). Nothing was
 * written. Published to clients as the Problem Details code {@code charge-has-payments}.
 */
public final class ChargeHasPaymentsException extends DomainException {

    public static final String CODE = "charge-has-payments";

    public ChargeHasPaymentsException() {
        super(CODE, "a charge with payments allocated to it is not voided");
    }
}
