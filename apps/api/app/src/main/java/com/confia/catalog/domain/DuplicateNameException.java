package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * The code of a payment concept is already taken in the institution, whatever its capitals. Published to clients as
 * the Problem Details code {@code duplicate-name}.
 */
public final class DuplicateNameException extends DomainException {

    public static final String CODE = "duplicate-name";

    public DuplicateNameException() {
        super(CODE, "a record with the same name, code or ordinal already exists");
    }
}
