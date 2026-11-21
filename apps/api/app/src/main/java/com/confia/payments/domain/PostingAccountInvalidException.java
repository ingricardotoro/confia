package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** An account that cannot be the account of the purpose it was given: cash and bank must be assets that carry no balance of a student. Published to clients as the Problem Details code {@code posting-account-invalid}. */
public final class PostingAccountInvalidException extends DomainException {

    public static final String CODE = "posting-account-invalid";

    public PostingAccountInvalidException() {
        super(CODE, "the account is not of the type its purpose requires");
    }
}
