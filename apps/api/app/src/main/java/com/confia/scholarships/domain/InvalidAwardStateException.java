package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** Approve and reject need a pending award, revoke needs an approved one. Published to clients as the Problem Details code {@code invalid-award-state}. */
public final class InvalidAwardStateException extends DomainException {

    public static final String CODE = "invalid-award-state";

    public InvalidAwardStateException() {
        super(CODE, "the award is not in the state this action needs");
    }
}
