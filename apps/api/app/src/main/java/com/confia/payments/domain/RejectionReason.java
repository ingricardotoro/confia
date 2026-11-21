package com.confia.payments.domain;

/**
 * Why a declared transfer was rejected: free text of 20 to 500 characters. It may name a person, so it lives only in the decision
 * record; it is never copied to the ledger or to the audit trail, and it never reaches a log through {@code toString}.
 */
public record RejectionReason(String text) {

    public static final int MIN_LENGTH = 20;
    public static final int MAX_LENGTH = 500;

    public static RejectionReason of(String value) {
        return new RejectionReason(FreeText.of(value, MIN_LENGTH, MAX_LENGTH));
    }

    @Override
    public String toString() {
        return "RejectionReason[length=" + text.codePointCount(0, text.length()) + "]";
    }
}
