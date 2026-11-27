package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** A campaign discount that waits for the approval of another person (or was rejected), or has a proposal waiting, is not changed. Published to clients as the Problem Details code {@code discount-pending-approval}. */
public final class DiscountPendingApprovalException extends DomainException {

    public static final String CODE = "discount-pending-approval";

    public DiscountPendingApprovalException() {
        super(CODE, "the discount waits for an approval or was rejected");
    }
}
