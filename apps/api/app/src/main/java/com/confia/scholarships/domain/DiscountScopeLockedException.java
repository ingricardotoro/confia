package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** The validity of a discount in use was widened, or a concept was added to it: it only narrows, and widening it is another discount. Published to clients as the Problem Details code {@code discount-scope-locked}. */
public final class DiscountScopeLockedException extends DomainException {

    public static final String CODE = "discount-scope-locked";

    public DiscountScopeLockedException() {
        super(CODE, "the validity of a discount in use only shortens and its concepts are only removed");
    }
}
