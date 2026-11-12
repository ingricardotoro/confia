package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * The section already holds as many active enrollments as its capacity. Published to clients as the Problem Details code {@code section-full}.
 */
public final class SectionFullException extends DomainException {

    public static final String CODE = "section-full";

    public SectionFullException() {
        super(CODE, "the section has no free seat");
    }
}
