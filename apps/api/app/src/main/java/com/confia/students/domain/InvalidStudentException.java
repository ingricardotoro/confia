package com.confia.students.domain;

import com.confia.kernel.DomainException;

/**
 * A value that breaks a rule of the student registry (blank or too long name, birth date in the future or
 * before 1990, unknown sex or status, malformed code or document). The edge validates the same rules
 * first, so this is the second line of defense. The message never repeats the rejected value. Published
 * to clients as the Problem Details code {@code validation-failed}.
 */
public final class InvalidStudentException extends DomainException {

    public static final String CODE = "validation-failed";

    public InvalidStudentException() {
        super(CODE, "the student value breaks a domain rule");
    }
}
