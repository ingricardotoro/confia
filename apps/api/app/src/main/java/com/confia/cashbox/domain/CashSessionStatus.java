package com.confia.cashbox.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where a cash session is: {@code open} accepts movements; {@code pending_approval} was closed with a difference and waits for
 * an approver who is not its cashier; {@code closed} is final. It only moves forward: open to closed or pending approval, and
 * pending approval to closed.
 */
public enum CashSessionStatus {
    OPEN("open"), PENDING_APPROVAL("pending_approval"), CLOSED("closed");

    private final String code;

    CashSessionStatus(String code) {
        this.code = code;
    }

    /** The value stored in the database and published by the API. */
    public String code() {
        return code;
    }

    public static Optional<CashSessionStatus> ofCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
