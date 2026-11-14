package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * Only an active student can be enrolled. Published to clients as the Problem Details code {@code student-not-active}.
 */
public final class StudentNotActiveException extends DomainException {

    public static final String CODE = "student-not-active";

    public StudentNotActiveException() {
        super(CODE, "the student is not active and cannot be enrolled");
    }
}
