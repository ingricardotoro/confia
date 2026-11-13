package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * A guardian with the same reference in the system it was imported from already exists in the institution. It is the
 * signal of an idempotent import (the row was imported before, or a concurrent application of the same file got there
 * first) and the import turns it into a skipped row; it is never published to a client as an error.
 */
public final class DuplicateExternalRefException extends DomainException {

    public static final String CODE = "duplicate-external-ref";

    public DuplicateExternalRefException() {
        super(CODE, "a guardian with the same external reference already exists in the institution");
    }
}
