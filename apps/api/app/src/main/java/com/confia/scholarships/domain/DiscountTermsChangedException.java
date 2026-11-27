package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The discount changed after the assignment was requested: it is not approved for terms it was not requested for. Published to clients as the Problem Details code {@code discount-terms-changed}. */
public final class DiscountTermsChangedException extends DomainException {

    public static final String CODE = "discount-terms-changed";

    public DiscountTermsChangedException() {
        super(CODE, "the discount changed after the assignment was requested");
    }
}
