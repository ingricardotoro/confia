package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * A link can only be given to an active guardian. Published to clients as the Problem Details code
 * {@code guardian-not-active}.
 */
public final class GuardianNotActiveException extends DomainException {

    public static final String CODE = "guardian-not-active";

    public GuardianNotActiveException() {
        super(CODE, "the guardian is not active and cannot be linked to a student");
    }
}
