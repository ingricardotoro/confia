package com.confia.imports.application;

/**
 * A reconciliation the record refuses: the reference is not the number of a document, or the total is out of range.
 * The edge validates the same bounds first; this is the second line of defense and it names the field, never the value.
 */
public final class RejectedReconciliationException extends RuntimeException {

    private final String field;

    public RejectedReconciliationException(String field) {
        super("the " + field + " is not acceptable");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
