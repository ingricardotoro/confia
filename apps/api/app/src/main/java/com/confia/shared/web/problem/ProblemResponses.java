package com.confia.shared.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a Problem Details response (RFC 9457) from a {@link ProblemCode} and the request, the same
 * way for the security chain's entry point, its access-denied handler, the firewall's rejection
 * handler and the last resort of the request context filter (web-edge-foundations design.md,
 * decision 8).
 *
 * <p>{@code title} and {@code detail} come from the catalog in the one fixed locale {@code es-HN}:
 * {@code Accept-Language} is never consulted while there is a single catalog. Nothing in a body
 * comes from an exception, so a message, a class name or a trace cannot reach a client. {@code
 * instance} is the request path without the query string, cut at {@value #MAX_INSTANCE_LENGTH}
 * characters; the trace id is the id the server generated for the request.
 */
public final class ProblemResponses {

    /**
     * The request attribute that holds the id the server generated for the request, set by the
     * request context filter and read here as the {@code traceId}.
     */
    public static final String REQUEST_ID_ATTRIBUTE =
            "com.confia.shared.web.request.RequestContextFilter.REQUEST_ID";

    static final Locale CATALOG_LOCALE = Locale.forLanguageTag("es-HN");

    private static final String MEDIA_TYPE = "application/problem+json";
    private static final int MAX_INSTANCE_LENGTH = 1024;
    private static final String NO_PATH = "/";
    private static final String CHALLENGE_HEADER = "WWW-Authenticate";
    private static final String BEARER = "Bearer";
    private static final String BEARER_INVALID_TOKEN = "Bearer error=\"invalid_token\"";

    private final MessageSource messages;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ProblemResponses(MessageSource messages) {
        this.messages = messages;
    }

    /** Writes {@code code} as the response. */
    public void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code)
            throws IOException {
        write(request, response, code, instanceOf(request), List.of());
    }

    /**
     * Writes {@code code} with the violated constraints in {@code errors}: the field and the
     * constraint of each, never a rejected value. An empty list is the same as none.
     */
    public void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code,
            List<FieldViolation> violations) throws IOException {
        write(request, response, code, instanceOf(request), violations);
    }

    /**
     * Writes {@code code} with {@value #NO_PATH} as the {@code instance}: for a request the
     * container itself refused, whose path was never decoded or validated and is not repeated to
     * the client at all.
     */
    public void writeWithoutRequestPath(HttpServletRequest request, HttpServletResponse response,
            ProblemCode code) throws IOException {
        write(request, response, code, NO_PATH, List.of());
    }

    private void write(HttpServletRequest request, HttpServletResponse response, ProblemCode code,
            String instance, List<FieldViolation> violations) throws IOException {
        ProblemBody body = new ProblemBody(code.type(),
                messages.getMessage(code.titleKey(), null, CATALOG_LOCALE), code.status(),
                messages.getMessage(code.detailKey(), null, CATALOG_LOCALE), instance,
                traceIdOf(request), violations);
        byte[] bytes = mapper.writeValueAsBytes(body);
        response.setStatus(code.status());
        String challenge = challengeOf(code);
        if (challenge != null) {
            response.setHeader(CHALLENGE_HEADER, challenge);
        }
        response.setContentType(MEDIA_TYPE);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /**
     * The {@code WWW-Authenticate} value RFC 9110 requires of every {@code 401}, decided by the code
     * and by nothing else, in this one place (session-tokens-and-web-layer design.md, decision 5):
     * no realm and no description, because the client needs neither and a description would only
     * offer text to an attacker. Any other status carries no challenge.
     */
    private static String challengeOf(ProblemCode code) {
        return switch (code) {
            case AUTHENTICATION_REQUIRED -> BEARER;
            case TOKEN_INVALID, TOKEN_EXPIRED -> BEARER_INVALID_TOKEN;
            default -> null;
        };
    }

    private static String instanceOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.length() > MAX_INSTANCE_LENGTH ? path.substring(0, MAX_INSTANCE_LENGTH) : path;
    }

    /** The server's request id; a request that somehow has none still gets a well-formed one. */
    private static String traceIdOf(HttpServletRequest request) {
        Object id = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return id instanceof String text ? text : UUID.randomUUID().toString();
    }
}
