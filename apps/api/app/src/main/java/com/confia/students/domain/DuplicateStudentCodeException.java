package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * The student code is already taken in the institution. Published to clients as the Problem Details code
 * {@code duplicate-student-code}.
 */
public final class DuplicateStudentCodeException extends DomainException {

    public static final String CODE = "duplicate-student-code";

    public DuplicateStudentCodeException() {
        super(CODE, "a student with the same code already exists in the institution");
    }
}
