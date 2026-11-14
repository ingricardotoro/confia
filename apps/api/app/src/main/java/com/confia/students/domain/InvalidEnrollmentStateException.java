package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * WITHDRAWN, TRANSFERRED and GRADUATED are final: only an active enrollment is transferred or exited, which also makes a repeated transition fail. Published to clients as the Problem Details code {@code invalid-enrollment-state}.
 */
public final class InvalidEnrollmentStateException extends DomainException {

    public static final String CODE = "invalid-enrollment-state";

    public InvalidEnrollmentStateException() {
        super(CODE, "the enrollment is not active, so it admits no transition");
    }
}
