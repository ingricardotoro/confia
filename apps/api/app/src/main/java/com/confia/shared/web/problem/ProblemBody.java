package com.confia.shared.web.problem;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.List;

/**
 * The body of every error response of the web edge: the five members of RFC 9457 plus {@code
 * traceId}, which the OpenAPI schema {@code ProblemDetail} already declares, and the {@code errors}
 * extension of a validation failure (web-edge-foundations design.md, decisions 8 and 9). One
 * record serializes the same way whether the security chain or the MVC translator wrote it, with
 * no dependency on Spring's own {@code ProblemDetail}.
 *
 * @param type the URI derived from the stable code
 * @param title the short text of the catalog
 * @param status the HTTP status, equal to the one of the response
 * @param detail the explanation of the catalog, never an exception message
 * @param instance the request path, without the query string
 * @param traceId the request id the server generated
 * @param errors the violated constraints of a validation failure; omitted from the JSON when
 *     there are none, so only {@code validation-failed} ever carries it
 */
public record ProblemBody(URI type, String title, int status, String detail, String instance,
        String traceId, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> errors) {

    /**
     * The most violations a response lists, whatever the client sent: a body with thousands of
     * invalid entries must not become a response of the same size.
     */
    public static final int MAX_ERRORS = 50;

    public ProblemBody {
        errors = errors == null ? List.of()
                : List.copyOf(errors.subList(0, Math.min(errors.size(), MAX_ERRORS)));
    }
}
