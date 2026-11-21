package com.confia.payments.domain;

/** What a person authorized decided about a declared transfer. */
public enum DecisionKind {
    CONFIRMED("confirmed"), REJECTED("rejected");

    private final String code;

    DecisionKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static DecisionKind ofCode(String code) {
        for (DecisionKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown decision");
    }

    /** The status a payment moves to with this decision. */
    public PaymentStatus resultingStatus() {
        return this == CONFIRMED ? PaymentStatus.CONFIRMED : PaymentStatus.REJECTED;
    }
}
