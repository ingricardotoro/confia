package com.confia.shared.web.problem;

import java.net.URI;

/**
 * The body of every error response of the web edge: the five members of RFC 9457 plus {@code
 * traceId}, which the OpenAPI schema {@code ProblemDetail} already declares (web-edge-foundations
 * design.md, decision 8). One record serializes the same way whether the security chain or the MVC
 * translator wrote it, with no dependency on Spring's own {@code ProblemDetail}. The {@code errors}
 * extension of a validation failure arrives with the translator that produces it.
 *
 * @param type the URI derived from the stable code
 * @param title the short text of the catalog
 * @param status the HTTP status, equal to the one of the response
 * @param detail the explanation of the catalog, never an exception message
 * @param instance the request path, without the query string
 * @param traceId the request id the server generated
 */
public record ProblemBody(URI type, String title, int status, String detail, String instance,
        String traceId) {
}
