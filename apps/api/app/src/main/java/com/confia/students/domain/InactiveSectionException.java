package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * The section is inactive, so nobody is enrolled in it. Same code as the one of the academic structure, which this module cannot import. Published to clients as the Problem Details code {@code inactive-reference}.
 */
public final class InactiveSectionException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveSectionException() {
        super(CODE, "the section is inactive");
    }
}
