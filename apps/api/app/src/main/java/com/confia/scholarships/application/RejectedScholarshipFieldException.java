package com.confia.scholarships.application;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * A rule of the scholarships about ONE member of the request body, named for the edge: the field and the keyword of the rule (for
 * example {@code percent} and {@code invalid}), never a value. The use case raises it from the domain's own error so the web layer,
 * which cannot see the domain, answers {@code validation-failed} listing the field. Without the edge's help it is still a plain
 * {@code 400 validation-failed}.
 */
public final class RejectedScholarshipFieldException extends DomainException {

    public static final String CODE = "validation-failed";

    private final String field;
    private final String reason;

    public RejectedScholarshipFieldException(String field, String reason) {
        super(CODE, "a field of the scholarship breaks a rule");
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
