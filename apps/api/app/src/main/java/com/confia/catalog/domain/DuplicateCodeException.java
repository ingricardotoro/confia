package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * The code of a payment concept is already taken in the institution, whatever its capitals. Published to clients as the
 * Problem Details code {@code duplicate-payment-concept-code}.
 */
public final class DuplicateCodeException extends DomainException {

    public static final String CODE = "duplicate-payment-concept-code";

    public DuplicateCodeException() {
        super(CODE, "a payment concept with the same code already exists");
    }
}
