package com.confia.charges.domain;

/** How often a charge plan accrues: every month, every two months, once a year or once. */
public enum PlanFrequency {
    MONTHLY, BIMONTHLY, ANNUAL, ONE_TIME;

    /** The frequency named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PlanFrequency parse(String value) {
        if (value != null) {
            for (PlanFrequency frequency : values()) {
                if (frequency.name().equals(value)) {
                    return frequency;
                }
            }
        }
        throw new InvalidChargesValueException();
    }
}
