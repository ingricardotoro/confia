package com.confia.payments.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The state of a payment (docs/02-modelo-de-dominio.md section 6.2), as this cut uses it: cash is born {@code confirmed}; a
 * transfer is born {@code declared} and becomes {@code confirmed}, or {@code rejected} when it is rejected. Only a confirmed
 * payment is posted and allocated. {@code draft} and the reversal of a confirmed payment belong to later cuts.
 */
public enum PaymentStatus {
    DECLARED("declared"), CONFIRMED("confirmed"), REJECTED("rejected");

    private final String code;

    PaymentStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<PaymentStatus> ofCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
