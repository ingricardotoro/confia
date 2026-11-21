package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** A payment cannot be posted because the cash and bank accounts of the map of payments (or the receivable of the charges) are not set, or one of them is inactive or of the wrong type. Nothing was written. Published to clients as the Problem Details code {@code posting-accounts-incomplete}. */
public final class PostingAccountsIncompleteException extends DomainException {

    public static final String CODE = "posting-accounts-incomplete";

    public PostingAccountsIncompleteException() {
        super(CODE, "the posting accounts of payments are incomplete or unusable");
    }
}
