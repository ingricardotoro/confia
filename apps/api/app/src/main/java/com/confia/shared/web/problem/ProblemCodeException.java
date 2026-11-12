package com.confia.shared.web.problem;

import com.confia.kernel.DomainException;
import java.util.List;
import java.util.Objects;

/**
 * Ends a request with a given code of the catalog (session-endpoints-cookie-and-csrf design.md,
 * decision 18). A controller of the session routes throws it when the outcome is a refusal that the
 * catalog already words, such as {@link ProblemCode#AUTHENTICATION_FAILED}; the single translator
 * answers it like any other domain exception, with the status, the {@code WWW-Authenticate}
 * challenge, the title and the detail the code owns. Headers added to the response before the throw,
 * such as the deletion of a cookie, are kept.
 *
 * <p>It carries nothing but the code, and optionally the violated constraints a client may act on (the field and
 * the rule, never a rejected value), which the translator lists in {@code errors}: a controller that must answer a
 * rule of its module with its own code and the field it is about (for example {@code
 * invalid-responsibility-split}) uses this form. Its message is fixed text that names the code and is never
 * written to a response, and it has no stack trace: it is the normal control flow of the edge, not
 * a failure to diagnose.
 */
public final class ProblemCodeException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final transient ProblemCode problem;
    private final transient List<FieldViolation> violations;

    public ProblemCodeException(ProblemCode code) {
        this(code, List.of());
    }

    /** Ends the request with {@code code} and lists {@code violations} in {@code errors}. */
    public ProblemCodeException(ProblemCode code, List<FieldViolation> violations) {
        super(Objects.requireNonNull(code, "code").code(), "the request ended with " + code.code());
        this.problem = code;
        this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

    /** The code of the catalog this exception ends the request with. */
    public ProblemCode problem() {
        return problem;
    }

    /** The violated constraints to list in {@code errors}; empty when there are none. */
    public List<FieldViolation> violations() {
        return violations;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
