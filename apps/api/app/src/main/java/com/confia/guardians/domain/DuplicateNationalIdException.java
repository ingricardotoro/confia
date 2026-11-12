package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * Another guardian of the institution already has this national identity document. The message never carries
 * the document. Published to clients as the Problem Details code {@code duplicate-national-id}.
 */
public final class DuplicateNationalIdException extends DomainException {

    public static final String CODE = "duplicate-national-id";

    public DuplicateNationalIdException() {
        super(CODE, "another guardian of the institution already has the same national identity document");
    }
}
