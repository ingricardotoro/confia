package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * An account that cannot be the account of the purpose it was given: the receivable must be an asset that carries the balance
 * of each student, the revenue an income account and the tax payable a liability, the last two without student balances.
 * Published to clients as the Problem Details code {@code posting-account-invalid}.
 */
public final class PostingAccountInvalidException extends DomainException {

    public static final String CODE = "posting-account-invalid";

    public PostingAccountInvalidException() {
        super(CODE, "the account is not of the type its purpose requires");
    }
}
