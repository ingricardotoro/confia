package com.confia.charges.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the charge plans (a blank or too long name, a due day outside 1 to 28, a number of
 * periods outside 1 to 36, a first period that is not the first day of a month, a value outside a set). The edge
 * validates the same rules first, so this is the second line of defense. Published to clients as the Problem Details code
 * {@code validation-failed}.
 */
public final class InvalidChargesValueException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidChargesValueException() {
        super(CODE, "the charge plan value breaks a domain rule");
    }
}
