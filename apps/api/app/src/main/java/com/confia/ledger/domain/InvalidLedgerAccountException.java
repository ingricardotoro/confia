package com.confia.ledger.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of an account of the chart: a code outside its shape, a blank or too long name, a type outside
 * the set, or the student flag on a type that cannot hold it. The edge validates the same rules first, so this is the second
 * line of defense. Published to clients as the Problem Details code {@code validation-failed}.
 */
public final class InvalidLedgerAccountException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidLedgerAccountException() {
        super(CODE, "the account of the chart breaks a domain rule");
    }
}
