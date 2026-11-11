package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * The identifier names no student in the institution of the request (a student of another institution
 * looks the same). Published to clients as the Problem Details code {@code resource-not-found}.
 */
public final class ResourceNotFoundException extends DomainException {

    public static final String CODE = "resource-not-found";

    public ResourceNotFoundException() {
        super(CODE, "the student does not exist in the institution of the request");
    }
}
