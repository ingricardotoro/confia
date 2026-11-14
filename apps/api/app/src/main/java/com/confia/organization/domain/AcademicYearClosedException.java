package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * A closed academic year is never edited or reopened, and no section is created in it. Published to clients as the Problem Details code {@code academic-year-closed}.
 */
public final class AcademicYearClosedException extends DomainException {

    public static final String CODE = "academic-year-closed";

    public AcademicYearClosedException() {
        super(CODE, "the academic year is closed");
    }
}
