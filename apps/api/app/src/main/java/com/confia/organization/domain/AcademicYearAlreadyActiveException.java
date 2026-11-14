package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * The institution already has an academic year with status ACTIVE; at most one is allowed. Published to clients as the Problem Details code {@code academic-year-already-active}.
 */
public final class AcademicYearAlreadyActiveException extends DomainException {

    public static final String CODE = "academic-year-already-active";

    public AcademicYearAlreadyActiveException() {
        super(CODE, "another academic year of the institution is already active");
    }
}
