package com.confia.shared.web.problem;

import com.confia.kernel.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one translator of exceptions raised inside the MVC layer into Problem Details responses
 * (web-edge-foundations design.md, decisions 8 and 9). Nothing in a response comes from an
 * exception: the body is built by {@link ProblemResponses} from a {@link ProblemCode} and the
 * catalog, and the only thing read from a validation failure is the field and the constraint that
 * failed, never the rejected value, the default message of the constraint or the parser's message.
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

    /** Used when a parameter has no name the compiler kept, so the field is never absent. */
    private static final String UNNAMED_PARAMETER = "parameter";

    private static final List<Class<? extends Annotation>> NAMED_BINDINGS =
            List.of(RequestParam.class, RequestHeader.class, PathVariable.class);

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
     * The three validation failures answer {@code 400 validation-failed} with the list of violated
     * fields. Two of them ({@link MethodArgumentNotValidException} and {@link
     * HandlerMethodValidationException}) are also an {@link ErrorResponse}; Spring picks the
     * handler closest to the exception's type, so these win over the generic branch of {@link
     * #unexpected}, which would answer the same status with no {@code errors}.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void invalidBody(MethodArgumentNotValidException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        answer(request, response, ProblemCode.VALIDATION_FAILED,
                violationsOf(e.getBindingResult().getAllErrors()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public void invalidParameters(HandlerMethodValidationException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        List<FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof Errors errors) {
                violations.addAll(violationsOf(errors.getAllErrors()));
            } else {
                String name = nameOf(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    violations.add(FieldViolation.of(name, constraintOf(error)));
                }
            }
        }
        answer(request, response, ProblemCode.VALIDATION_FAILED, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public void invalidConstraints(ConstraintViolationException e, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        List<FieldViolation> violations = new ArrayList<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            violations.add(FieldViolation.of(fieldOf(violation.getPropertyPath()),
                    violation.getConstraintDescriptor().getAnnotation().annotationType()
                            .getSimpleName()));
        }
        answer(request, response, ProblemCode.VALIDATION_FAILED, violations);
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

    private void answer(HttpServletRequest request, HttpServletResponse response, ProblemCode code,
            List<FieldViolation> violations) throws IOException {
        if (!response.isCommitted()) {
            problems.write(request, response, code, violations);
        }
    }

    /**
     * The violations of a binding result. A violation of the whole object has no field and so an
     * empty one. Only the field and the constraint are read: never the rejected value or the
     * message.
     */
    private static List<FieldViolation> violationsOf(List<? extends ObjectError> errors) {
        List<FieldViolation> violations = new ArrayList<>();
        for (ObjectError error : errors) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : "";
            violations.add(FieldViolation.of(field, constraintOf(error)));
        }
        return violations;
    }

    /**
     * The name of the constraint, such as {@code Size}: the part before the first dot of the last
     * code of the error. A field error ends in the bare name ({@code Size}); a parameter error ends
     * in the name and the type of the value ({@code Max.int}). An error with no codes is just
     * {@code invalid}.
     */
    private static String constraintOf(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        if (codes == null || codes.length == 0) {
            return "invalid";
        }
        String last = codes[codes.length - 1];
        int dot = last.indexOf('.');
        return dot < 0 ? last : last.substring(0, dot);
    }

    /**
     * The name a request binding gives the parameter ({@code @RequestParam}, {@code
     * @RequestHeader} or {@code @PathVariable}), which is what the client knows it by. The build
     * does not keep parameter names, so the compiler's own name is only a fallback.
     */
    private static String nameOf(MethodParameter parameter) {
        MergedAnnotations annotations = MergedAnnotations.from(parameter.getParameterAnnotations());
        for (Class<? extends Annotation> binding : NAMED_BINDINGS) {
            MergedAnnotation<? extends Annotation> bound = annotations.get(binding);
            if (bound.isPresent() && !bound.getString("name").isEmpty()) {
                return bound.getString("name");
            }
        }
        String name = parameter.getParameterName();
        return name == null ? UNNAMED_PARAMETER : name;
    }

    /** The path of a violation, without the name of the method that was validated. */
    private static String fieldOf(Path path) {
        String text = path.toString();
        Path.Node first = path.iterator().next();
        boolean method = first.getKind() == ElementKind.METHOD
                || first.getKind() == ElementKind.CONSTRUCTOR;
        String prefix = first.getName() + ".";
        return method && text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }
}
