package com.confia.students.application;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A member of an enrollment request that breaks a rule, named for the edge: the field and the constraint,
 * never the value. The use case raises it from the domain's own error so the web layer, which cannot see the
 * domain, answers the same {@code validation-failed} list of violations as Bean Validation does. Without
 * the edge's help it is still a plain {@code 400 validation-failed}.
 */
public final class RequestFieldException extends DomainException {

    public static final String CODE = "validation-failed";

    private final String field;
    private final String reason;

    public RequestFieldException(String field, String reason) {
        super(CODE, "a member of the request breaks a rule");
        this.field = Objects.requireNonNull(field, "field");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
