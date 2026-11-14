package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * Nobody is enrolled, transferred or withdrawn in a closed academic year. Same code as the one of the academic structure, which this module cannot import. Published to clients as the Problem Details code {@code academic-year-closed}.
 */
public final class ClosedAcademicYearException extends DomainException {

    public static final String CODE = "academic-year-closed";

    public ClosedAcademicYearException() {
        super(CODE, "the academic year is closed");
    }
}
