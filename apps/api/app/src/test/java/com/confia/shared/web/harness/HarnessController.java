package com.confia.shared.web.harness;

import com.confia.kernel.DomainException;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controllers behind the real security chain. They answer every HTTP method, so a
 * response that is not the chain's own denial is visible as a {@code 2xx}: the chain, not the
 * mapping, is what the tests prove.
 */
@RestController
class HarnessController {

    private final Calls calls;

    HarnessController(Calls calls) {
        this.calls = calls;
    }

    /** Registered and public: the harness adds it to the allow-list for {@code GET} only. */
    @RequestMapping("/test/open")
    String open() {
        calls.openInvoked();
        return "open";
    }

    /** Registered and not on the allow-list: every request must be denied before this runs. */
    @RequestMapping("/x")
    String protectedRoute() {
        calls.protectedInvoked();
        return "protected";
    }

    /**
     * Public for {@code GET}: answers with what the request context holds for this request, so a
     * test can compare it with what the client sent. The two ids are the one in the request
     * attribute and the one in the scoped origin; they must be the same value.
     */
    @GetMapping("/test/origin")
    OriginView origin(HttpServletRequest request) {
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        return new OriginView(origin.requestId().toString(),
                (String) request.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE),
                origin.clientAddress() == null ? null : origin.clientAddress().canonical(),
                origin.userAgent());
    }

    /** What {@link #origin} reports; a missing agent is {@code null}. */
    record OriginView(String requestId, String requestIdAttribute, String sourceIp,
            String userAgent) {
    }

    /** The body the validated route accepts: a mandatory name of at most ten characters. */
    record ValidatedBody(@NotBlank @Size(max = 10) String name,
            @Valid Map<String, Inner> props) {
    }

    /** The value of a map entry of the validated body: a mandatory field. */
    record Inner(@NotBlank String campo) {
    }

    /** Public for {@code POST}, JSON only: the routes of validation, malformed bodies and 415. */
    @PostMapping(path = "/test/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
    String validated(@Valid @RequestBody ValidatedBody body) {
        return "accepted";
    }

    /**
     * Public for {@code GET}: a constraint on a request parameter and another on a header, checked
     * by Spring's method validation. Both are named explicitly, because the build does not keep
     * parameter names.
     */
    @GetMapping("/test/bounded")
    String bounded(@RequestParam(name = "size") @Max(5) int size,
            @RequestHeader(name = "X-Limit", defaultValue = "0") @Max(3) int limit) {
        return "size " + size + limit;
    }

    /** Public for {@code GET}: a mandatory header and a mandatory, typed parameter. */
    @GetMapping("/test/required")
    String required(@RequestHeader(name = "X-Needed") String header,
            @RequestParam(name = "count") int count) {
        return header + count;
    }

    /** Public for {@code GET}: a violation set raised the way a validated service raises it. */
    @GetMapping("/test/constraint-violation")
    String constraintViolation() {
        Set<ConstraintViolation<ValidatedBody>> violations = VALIDATOR
                .validate(new ValidatedBody(HarnessProcess.SENSITIVE_VALUE, null));
        throw new ConstraintViolationException(violations);
    }

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory()
            .getValidator();

    /** A business rule whose code the catalog knows, with an internal message that holds a document id. */
    static final class KnownDomainFailure extends DomainException {
        KnownDomainFailure() {
            super("forbidden", "internal: student document 0801-1990-12345 may not pay");
        }
    }

    /** A business rule whose code the catalog does not know. */
    static final class UnknownDomainFailure extends DomainException {
        UnknownDomainFailure() {
            super("payment-frozen", "internal: ledger row 8841 is frozen");
        }
    }

    /** Public for {@code GET}: the catalog knows the code. */
    @GetMapping("/test/domain-known")
    String domainKnown() {
        throw new KnownDomainFailure();
    }

    /** Public for {@code GET}: the catalog does not know the code. */
    @GetMapping("/test/domain-unknown")
    String domainUnknown() {
        throw new UnknownDomainFailure();
    }
/** Public for {@code GET}: a database failure whose root cause carries a socket's words. */    @GetMapping("/test/sql-reset")    String sqlReset() throws SQLException {        throw new SQLException("could not read the ledger",                new SocketException("Connection reset by peer"));    }    /** Registered for {@code POST} only and public for {@code GET}: a method the route does not serve. */    @PostMapping("/test/post-only")    String postOnly() {        return "posted";    }    /** Public for {@code GET}: fails the way a controller does with {@code ResponseStatusException}. */    @GetMapping("/test/status")    String status(@RequestParam(name = "code") int code) {        throw new ResponseStatusException(HttpStatusCode.valueOf(code), "secret reason");    }    /** Public for {@code GET}: a security exception raised inside the MVC layer. */    @GetMapping("/test/access-denied")    String accessDenied() {        throw new AccessDeniedException("secret reason");    }

    /** Public for {@code GET}: what the container raises when the client is gone. */
    @GetMapping("/test/disconnected")
    String disconnected() throws AsyncRequestNotUsableException {
        throw new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe");
    }

    /** Public for {@code GET} and always failing, with a message that must never reach a client. */
    @RequestMapping("/test/boom")
    String boom(HttpServletRequest request) {
        calls.failingInvoked(
                (String) request.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE),
                MDC.get(RequestContextFilter.MDC_KEY));
        throw new IllegalStateException("jdbc:postgresql://host/db password=x");
    }
}
