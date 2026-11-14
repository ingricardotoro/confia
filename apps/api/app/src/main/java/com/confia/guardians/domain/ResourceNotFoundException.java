package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * The identifier names no guardian (or no student) in the institution of the request; a record of another
 * institution looks the same. Published to clients as the Problem Details code {@code resource-not-found}.
 */
public final class ResourceNotFoundException extends DomainException {

    public static final String CODE = "resource-not-found";

    public ResourceNotFoundException() {
        super(CODE, "the record does not exist in the institution of the request");
    }
}
