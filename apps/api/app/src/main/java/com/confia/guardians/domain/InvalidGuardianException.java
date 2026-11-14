package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the guardian registry (blank or too long name, malformed document, RTN, e-mail
 * or phone, unknown status, channel or relationship, a percentage outside 0.01 to 100 or with more than two
 * decimals). The edge validates the same rules first, so this is the second line of defense. The message never
 * repeats the rejected value. Published to clients as the Problem Details code {@code validation-failed}.
 */
public final class InvalidGuardianException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidGuardianException() {
        super(CODE, "the guardian value breaks a domain rule");
    }
}
