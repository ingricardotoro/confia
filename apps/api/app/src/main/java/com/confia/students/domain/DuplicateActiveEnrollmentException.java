package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * INV-31: a student has at most one active enrollment per academic year. Published to clients as the Problem Details code {@code duplicate-active-enrollment}.
 */
public final class DuplicateActiveEnrollmentException extends DomainException {

    public static final String CODE = "duplicate-active-enrollment";

    public DuplicateActiveEnrollmentException() {
        super(CODE, "the student already has an active enrollment in the academic year");
    }
}
