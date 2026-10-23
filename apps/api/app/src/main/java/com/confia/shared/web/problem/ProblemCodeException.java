package com.confia.shared.web.problem;

import com.confia.kernel.DomainException;
import java.util.Objects;

/**
 * Ends a request with a given code of the catalog (session-endpoints-cookie-and-csrf design.md,
 * decision 18). A controller of the session routes throws it when the outcome is a refusal that the
 * catalog already words, such as {@link ProblemCode#AUTHENTICATION_FAILED}; the single translator
 * answers it like any other domain exception, with the status, the {@code WWW-Authenticate}
 * challenge, the title and the detail the code owns. Headers added to the response before the throw,
 * such as the deletion of a cookie, are kept.
 *
 * <p>It carries nothing but the code. Its message is fixed text that names the code and is never
 * written to a response, and it has no stack trace: it is the normal control flow of the edge, not
 * a failure to diagnose.
 */
public final class ProblemCodeException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final transient ProblemCode problem;

    public ProblemCodeException(ProblemCode code) {
        super(Objects.requireNonNull(code, "code").code(), "the request ended with " + code.code());
        this.problem = code;
    }

    /** The code of the catalog this exception ends the request with. */
    public ProblemCode problem() {
        return problem;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
