package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** The same bank and reference are already used by a payment that was not rejected. Published to clients as the Problem Details code {@code duplicate-transfer-reference}. */
public final class DuplicateTransferReferenceException extends DomainException {

    public static final String CODE = "duplicate-transfer-reference";

    public DuplicateTransferReferenceException() {
        super(CODE, "the bank and the reference are already declared");
    }
}
