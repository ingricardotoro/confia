package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * A grade needs an active modality and a new section needs an active grade. Published to clients as the Problem Details code {@code inactive-reference}.
 */
public final class InactiveReferenceException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveReferenceException() {
        super(CODE, "the referenced record is inactive");
    }
}
