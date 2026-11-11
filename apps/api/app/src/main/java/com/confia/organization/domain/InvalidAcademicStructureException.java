package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the academic structure (blank name, end date not after the start date, capacity not above zero). The edge validates the same rules first, so this is the second line of defense. Published to clients as the Problem Details code {@code validation-failed}.
 */
public final class InvalidAcademicStructureException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidAcademicStructureException() {
        super(CODE, "the academic structure value breaks a domain rule");
    }
}
