package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * Another run of the same plan and period is queued or running and the request carries another key (INV-48). Published to
 * clients as the Problem Details code {@code accrual-run-in-progress}.
 */
public final class AccrualRunInProgressException extends DomainException {

    public static final String CODE = "accrual-run-in-progress";

    public AccrualRunInProgressException() {
        super(CODE, "another accrual run of this plan and period is queued or running");
    }
}
