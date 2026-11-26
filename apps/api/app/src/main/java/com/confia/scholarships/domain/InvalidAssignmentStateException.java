package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** Approve and reject need a pending assignment, revoke needs an approved one. Published to clients as the Problem Details code {@code invalid-discount-assignment-state}. */
public final class InvalidAssignmentStateException extends DomainException {

    public static final String CODE = "invalid-discount-assignment-state";

    public InvalidAssignmentStateException() {
        super(CODE, "the assignment is not in the state this action needs");
    }
}
