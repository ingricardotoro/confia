package com.confia.charges.domain;

/** The state of a charge plan: a draft, then active, then archived; it never goes back. */
public enum PlanStatus {
    DRAFT, ACTIVE, ARCHIVED;

    /** The status named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PlanStatus parse(String value) {
        if (value != null) {
            for (PlanStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
        }
        throw new InvalidChargesValueException();
    }
}
