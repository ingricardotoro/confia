package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * The links of an inactive student do not change. Published to clients as the Problem Details code
 * {@code student-not-active}.
 */
public final class StudentNotActiveException extends DomainException {

    public static final String CODE = "student-not-active";

    public StudentNotActiveException() {
        super(CODE, "the student is not active and its guardians cannot change");
    }
}
