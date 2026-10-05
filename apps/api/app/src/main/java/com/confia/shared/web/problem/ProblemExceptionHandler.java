package com.confia.shared.web.problem;

import com.confia.kernel.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one translator of exceptions raised inside the MVC layer into Problem Details responses
 * (web-edge-foundations design.md, decisions 8 and 9). Nothing in a response comes from an
 * exception: the body is built by {@link ProblemResponses} from a {@link ProblemCode} and the
 * catalog.
 *
 * <p>Every handler logs before it writes, so what a test or an operator reads from the log was
 * logged before the client had its answer, and none writes to a response that is already
 * committed. The unforeseen failure is logged in full, with the request id the logging context
 * already holds (the same one the body carries as {@code traceId}).
 *
 * <p>It runs ahead of any other advice. A security exception is not an unforeseen failure: it is
 * rethrown, so the security chain, which knows how to answer {@code 401} and {@code 403}, does.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ProblemExceptionHandler {

    /**
     * The status left on a response nobody will read, because the client closed the connection
     * first. It is the status nginx records for the same event: not a {@code 2xx}, so no access log
     * or metric ever counts the request as a success, and not a {@code 5xx}, so a client that
     * walked away does not raise a server alarm.
     */
    public static final int CLIENT_CLOSED_REQUEST = 499;

    private static final Logger LOG = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private static final int MAX_CAUSES = 16;

    private final ProblemResponses problems;

    public ProblemExceptionHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    /**
     * A business rule's error: the code of the exception picks the response. A code the catalog
     * does not know is an {@code internal-error} that never repeats that code to the client, and a
     * known code whose status is a server error is logged like any other server failure.
     */
    @ExceptionHandler(DomainException.class)
    public void domain(DomainException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Optional<ProblemCode> code = ProblemCode.ofCode(e.code());
        if (code.isEmpty()) {
            // The code is a validated kebab-case identifier, safe to name in the server log.
            LOG.error("A domain exception carries the code {}, which the catalog does not know",
                    e.code(), e);
        } else if (code.get().status() >= 500) {
            LOG.error("A domain exception answered with a server error", e);
        }
        answer(request, response, code.orElse(ProblemCode.INTERNAL_ERROR));
    }

    /**
     * A body that cannot be read, a missing header or parameter, or a value of the wrong type: a
     * {@code validation-failed} with no violation list and none of the parser's or the
     * converter's text.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public void unreadableRequest(Exception e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        answer(request, response, ProblemCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void unsupportedMediaType(HttpMediaTypeNotSupportedException e,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        answer(request, response, ProblemCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void noResource(NoResourceFoundException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        answer(request, response, ProblemCode.RESOURCE_NOT_FOUND);
    }

    /**
     * Everything else, in this order. A security exception is rethrown for the security chain. A
     * failure whose causes include a write-side type of the container or of Spring means the
     * client went away: nothing is written. A Spring {@link ErrorResponse} already carries the
     * status its author chose, which {@link ProblemCode#forStatus} turns into the catalog's code
     * (a {@code 405} keeps its {@code Allow} header), and only a server error is logged. Anything
     * else is a {@code 500 internal-error}, logged in full with the request id.
     *
     * @throws AccessDeniedException for the security chain to answer
     * @throws AuthenticationException for the security chain to answer
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (e instanceof AccessDeniedException denied) {
            throw denied;
        }
        if (e instanceof AuthenticationException unauthenticated) {
            throw unauthenticated;
        }
        if (clientWentAway(e)) {
            LOG.debug("The client closed the connection before the response was written");
            if (!response.isCommitted()) {
                response.setStatus(CLIENT_CLOSED_REQUEST);
            }
            return;
        }
        if (e instanceof ErrorResponse framework) {
            frameworkError(framework, e, request, response);
            return;
        }
        LOG.error("An exception was not translated by any specific handler", e);
        answer(request, response, ProblemCode.INTERNAL_ERROR);
    }

    private void frameworkError(ErrorResponse framework, Exception e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        ProblemCode code = ProblemCode.forStatus(framework.getStatusCode().value());
        if (code.status() >= 500) {
            LOG.error("A framework error answered with a server error", e);
        }
        if (code == ProblemCode.METHOD_NOT_ALLOWED && !response.isCommitted()) {
            String allow = framework.getHeaders().getFirst(HttpHeaders.ALLOW);
            if (allow != null) {
                response.setHeader(HttpHeaders.ALLOW, allow);
            }
        }
        answer(request, response, code);
    }

    /**
     * Whether the failure is the server failing to write to a client that left: an {@link
     * AsyncRequestNotUsableException} of Spring or a {@link ClientAbortException} of Tomcat, which
     * is how each wraps the write-side I/O error, anywhere in the cause chain. The text of a
     * message is never consulted: "Connection reset by peer" is also what a database, a cache or a
     * mail server says when its connection drops, and that is a failure of the operation.
     */
    private static boolean clientWentAway(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++) {
            if (cause instanceof AsyncRequestNotUsableException
                    || cause instanceof ClientAbortException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Writes {@code code} unless the response is already committed: nothing can be added then. */
    private void answer(HttpServletRequest request, HttpServletResponse response, ProblemCode code)
            throws IOException {
        if (!response.isCommitted()) {
            problems.write(request, response, code);
        }
    }
}
