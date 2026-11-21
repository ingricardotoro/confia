package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** Only a declared transfer is confirmed or rejected, once. Published to clients as the Problem Details code {@code payment-not-declared}. */
public final class PaymentNotDeclaredException extends DomainException {

    public static final String CODE = "payment-not-declared";

    public PaymentNotDeclaredException() {
        super(CODE, "the payment is not a declared transfer");
    }
}
