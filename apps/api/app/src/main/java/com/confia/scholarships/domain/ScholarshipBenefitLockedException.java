package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The type or the value of the benefit of a scholarship that has approved awards: another scholarship is created. Published to clients as the Problem Details code {@code scholarship-benefit-locked}. */
public final class ScholarshipBenefitLockedException extends DomainException {

    public static final String CODE = "scholarship-benefit-locked";

    public ScholarshipBenefitLockedException() {
        super(CODE, "the benefit of a scholarship with approved awards does not change");
    }
}
