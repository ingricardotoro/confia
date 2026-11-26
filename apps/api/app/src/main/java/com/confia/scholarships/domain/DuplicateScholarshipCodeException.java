package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The code is in use by another scholarship of the institution, whatever its capitals. Published to clients as the Problem Details code {@code duplicate-scholarship-code}. */
public final class DuplicateScholarshipCodeException extends DomainException {

    public static final String CODE = "duplicate-scholarship-code";

    public DuplicateScholarshipCodeException() {
        super(CODE, "the scholarship code is already in use in the institution");
    }
}
