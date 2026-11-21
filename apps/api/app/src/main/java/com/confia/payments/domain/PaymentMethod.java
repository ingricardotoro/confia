package com.confia.payments.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * How the money arrived. Cash is collected in the open session of the cashier and is born confirmed; a bank transfer is declared
 * by the cashier and confirmed later by a person authorized (docs/cortes/payments-register.md).
 */
public enum PaymentMethod {
    CASH(PostingPurpose.CASH), BANK_TRANSFER(PostingPurpose.BANK);

    private final PostingPurpose purpose;

    PaymentMethod(PostingPurpose purpose) {
        this.purpose = purpose;
    }

    /** The account of the chart that is debited when a payment of this method is posted. */
    public PostingPurpose purpose() {
        return purpose;
    }

    public boolean requiresCashSession() {
        return this == CASH;
    }

    public static Optional<PaymentMethod> ofName(String name) {
        return Arrays.stream(values()).filter(method -> method.name().equals(name)).findFirst();
    }
}
