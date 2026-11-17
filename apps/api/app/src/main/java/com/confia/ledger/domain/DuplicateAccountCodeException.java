package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/** A second account with a code the institution already has, whatever the capitals. Published as {@code duplicate-account-code}. */
public final class DuplicateAccountCodeException extends DomainException {

    public static final String CODE = "duplicate-account-code";

    public DuplicateAccountCodeException() {
        super(CODE, "the institution already has an account with this code");
    }
}
