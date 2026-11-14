package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * The date range of an academic year shares at least one day with another year of the same institution (both ends are inclusive). Published to clients as the Problem Details code {@code academic-year-overlap}.
 */
public final class AcademicYearOverlapException extends DomainException {

    public static final String CODE = "academic-year-overlap";

    public AcademicYearOverlapException() {
        super(CODE, "the date range overlaps another academic year of the institution");
    }
}
