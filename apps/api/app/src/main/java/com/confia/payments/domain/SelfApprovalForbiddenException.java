package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** Segregation of duties: whoever registered a transfer neither confirms nor rejects it. Published to clients as the Problem Details code {@code self-approval-forbidden}. */
public final class SelfApprovalForbiddenException extends DomainException {

    public static final String CODE = "self-approval-forbidden";

    public SelfApprovalForbiddenException() {
        super(CODE, "the one who registered the payment cannot decide it");
    }
}
