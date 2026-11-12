package com.confia.students.domain;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A value of an enrollment request that breaks a rule of the cut: a date outside its range, a missing or
 * out-of-bounds reason, an unknown status, a transfer to the section the enrollment already is in. It names
 * the field and the constraint it broke (never the rejected value), so the edge can answer the same list
 * of violations as Bean Validation does. Published to clients as the Problem Details code {@code
 * validation-failed}.
 */
public final class InvalidEnrollmentException extends DomainException {

    public static final String CODE = "validation-failed";

    /** The reason of a date that is out of its range or in the future ({@code enrollment-date-out-of-range}). */
    public static final String DATE_OUT_OF_RANGE = "enrollment-date-out-of-range";

    private final String field;
    private final String reason;

    public InvalidEnrollmentException(String field, String reason) {
        super(CODE, "the enrollment value breaks a domain rule");
        this.field = Objects.requireNonNull(field, "field");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** The member of the request, for example {@code enrolledOn}. */
    public String field() {
        return field;
    }

    /** The constraint, in kebab case, for example {@code size}. */
    public String reason() {
        return reason;
    }
}
