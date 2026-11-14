package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * A transfer between sections stays inside the academic year of the enrollment. Published to clients as the Problem Details code {@code transfer-year-mismatch}.
 */
public final class TransferYearMismatchException extends DomainException {

    public static final String CODE = "transfer-year-mismatch";

    public TransferYearMismatchException() {
        super(CODE, "the target section belongs to another academic year");
    }
}
