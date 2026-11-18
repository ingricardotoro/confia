package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/**
 * The deactivation of an account that another module still points at (the posting map of the charges): the new facts would
 * be refused. Published as {@code account-in-use}; the map is pointed at another account first.
 */
public final class AccountInUseException extends DomainException {

    public static final String CODE = "account-in-use";

    public AccountInUseException() {
        super(CODE, "the account is in use by a posting map and cannot be deactivated");
    }
}
