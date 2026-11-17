package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** Published to clients as the Problem Details code {@code student-account-under-review}. */
public final class StudentAccountUnderReviewException extends DomainException {

    public static final String CODE = "student-account-under-review";

    public StudentAccountUnderReviewException() {
        super(CODE, "the student account is under review and accepts no new writes");
    }
}
