package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/** Segregation of duties: whoever requested an award neither approves nor rejects it. Published to clients as the Problem Details code {@code self-approval-forbidden}. */
public final class SelfApprovalForbiddenException extends DomainException {

    public static final String CODE = "self-approval-forbidden";

    public SelfApprovalForbiddenException() {
        super(CODE, "the one who requested the award cannot decide it");
    }
}
