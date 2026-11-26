package com.confia.scholarships.domain;

/**
 * Why an award was rejected or revoked: free text of 20 to 500 characters. It may name a person, so it lives only in the decision
 * record; it is never copied to the audit trail (only its SHA-256 and its length are), and it never reaches a log through {@code toString}.
 */
public record DecisionReason(String text) {

    public static final int MIN_LENGTH = 20;
    public static final int MAX_LENGTH = 500;

    public static DecisionReason of(String value) {
        return new DecisionReason(Texts.free(value, MIN_LENGTH, MAX_LENGTH, "reason"));
    }

    /** The length in characters, the only thing of the reason that the audit trail records. */
    public int length() {
        return text.codePointCount(0, text.length());
    }

    @Override
    public String toString() {
        return "DecisionReason[length=" + length() + "]";
    }
}
