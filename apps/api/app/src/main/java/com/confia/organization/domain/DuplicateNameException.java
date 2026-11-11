package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * A name, code or ordinal that must be unique within its scope is already taken. Published to clients as the Problem Details code {@code duplicate-name}.
 */
public final class DuplicateNameException extends DomainException {

    public static final String CODE = "duplicate-name";

    public DuplicateNameException() {
        super(CODE, "a record with the same name, code or ordinal already exists");
    }
}
