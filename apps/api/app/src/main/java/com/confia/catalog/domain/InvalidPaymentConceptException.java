package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the catalog (blank or too long code or name, a category outside the set). The edge
 * validates the same rules first, so this is the second line of defense. Published to clients as the Problem Details
 * code {@code validation-failed}.
 */
public final class InvalidPaymentConceptException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidPaymentConceptException() {
        super(CODE, "the catalog value breaks a domain rule");
    }
}
