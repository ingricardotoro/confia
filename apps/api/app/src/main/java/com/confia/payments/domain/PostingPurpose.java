package com.confia.payments.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * What an account of the chart is used for when a payment is posted: the cash account (debited by cash) and the bank account
 * (debited by a confirmed transfer). Both must be assets that carry no balance of a student; the receivable that is credited
 * is the one of the charges and is not part of this map.
 */
public enum PostingPurpose {
    CASH, BANK;

    /** The type the account of every purpose must have. */
    public static final String ACCOUNT_TYPE = "ASSET";

    /** Whether an account of this type and student flag may be the account of any purpose of this map. */
    public boolean accepts(String type, boolean holdsStudentBalances) {
        return ACCOUNT_TYPE.equals(type) && !holdsStudentBalances;
    }

    public static Optional<PostingPurpose> ofName(String name) {
        return Arrays.stream(values()).filter(purpose -> purpose.name().equals(name)).findFirst();
    }

    /** The purpose named by the text, or a validation error (case matters, as in the API). */
    public static PostingPurpose parse(String name) {
        return ofName(name).orElseThrow(InvalidPaymentsValueException::new);
    }
}
