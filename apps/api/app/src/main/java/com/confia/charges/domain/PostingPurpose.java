package com.confia.charges.domain;

/** What an account of the chart is used for when a charge is posted: the receivable, the revenue and the tax payable. */
public enum PostingPurpose {
    RECEIVABLE, REVENUE, TAX_PAYABLE;

    /** The purpose named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PostingPurpose parse(String value) {
        if (value != null) {
            for (PostingPurpose purpose : values()) {
                if (purpose.name().equals(value)) {
                    return purpose;
                }
            }
        }
        throw new InvalidChargesValueException();
    }
}
