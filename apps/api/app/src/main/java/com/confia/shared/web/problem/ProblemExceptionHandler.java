package com.confia.shared.web.problem;

import com.confia.kernel.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The one translator of exceptions raised inside the MVC layer into Problem Details responses
 * (web-edge-foundations design.md, decisions 8 and 9). Nothing in a response comes from an
 * exception: the body is built by {@link ProblemResponses} from a {@link ProblemCode} and the
 * catalog.
 *
 * <p>Every handler logs before it writes, so what a test or an operator reads from the log was
 * logged before the client had its answer. The unforeseen failure is logged in full, with the
 * request id the logging context already holds (the same one the body carries as {@code traceId}),
 * and a client that went away is logged at {@code DEBUG} with no data at all.
 *
 * <p>It runs ahead of any other advice. A security exception raised outside the MVC layer never
 * reaches it: the security chain answers those itself.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ProblemExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private final ProblemResponses problems;

    public ProblemExceptionHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    /**
     * A business rule's error: the code of the exception picks the response, and a code the
     * catalog does not know is an {@code internal-error} that never repeats that code.
     */
    @ExceptionHandler(DomainException.class)
    public void domain(DomainException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Optional<ProblemCode> code = ProblemCode.ofCode(e.code());
        if (code.isEmpty()) {
            LOG.error("A domain exception carries a code the catalog does not know", e);
        }
        problems.write(request, response, code.orElse(ProblemCode.INTERNAL_ERROR));
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
        problems.write(request, response, ProblemCode.VALIDATION_FAILED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void unsupportedMediaType(HttpMediaTypeNotSupportedException e,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, ProblemCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void noResource(NoResourceFoundException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        problems.write(request, response, ProblemCode.RESOURCE_NOT_FOUND);
    }

    /**
     * Everything else. A client that went away is not an error and gets nothing written; any other
     * exception is a {@code 500 internal-error}, logged in full with the request id.
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
            LOG.debug("The client closed the connection before the response was written");
            return;
        }
        LOG.error("An exception was not translated by any specific handler", e);
        if (response.isCommitted()) {
            return;
        }
        problems.write(request, response, ProblemCode.INTERNAL_ERROR);
    }
}
