package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * An item for a payment concept that is deactivated. Published to clients as the Problem Details code {@code
 * inactive-reference}, which the academic structure and the catalog already use.
 */
public final class InactiveReferenceException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveReferenceException() {
        super(CODE, "the referenced resource is inactive");
    }
}
