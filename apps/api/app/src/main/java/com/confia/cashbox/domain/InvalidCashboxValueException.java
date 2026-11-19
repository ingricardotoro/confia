package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** A value breaks a rule of the cash sessions (a negative float, a non-positive amount, more than two decimals, a bad note). The edge validates the same rules first. Published to clients as the Problem Details code {@code validation-failed}. */
public final class InvalidCashboxValueException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidCashboxValueException() {
        super(CODE, "the cash session value breaks a domain rule");
    }
}
