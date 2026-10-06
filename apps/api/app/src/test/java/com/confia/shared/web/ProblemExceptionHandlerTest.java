package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.DomainException;
import com.confia.shared.web.problem.CapacityExceededException;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemExceptionHandler;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.ratelimit.TooManyRequestsException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The branches of {@link ProblemExceptionHandler} that the harness cannot reach with a real client:
 * a response that is already committed, and the shapes in which a vanished client shows up. The
 * answers on the wire are proven in {@code ProblemTranslationTest}.
 */
class ProblemExceptionHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private Level levelBefore;

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler(
            new ProblemResponses(catalog()));

    private static StaticMessageSource catalog() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), java.util.Locale.forLanguageTag("es-HN"), "title");
            messages.addMessage(code.detailKey(), java.util.Locale.forLanguageTag("es-HN"), "detail");
        }
        return messages;
    }

    @BeforeEach
    void capture() {
        levelBefore = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(levelBefore);
    }

    @Test
    void aResponseThatIsAlreadyCommittedIsNotWrittenToAndTheExceptionIsStillLogged()
            throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setCommitted(true);

        handler.unexpected(new IllegalStateException("half the body already left"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logged.list.get(0).getThrowableProxy().getMessage())
                .isEqualTo("half the body already left");
    }

    /**
     * An I/O error is a vanished client only when the server's own write side says so, by the type
     * that wraps it. The same words come out of a database, a cache or a mail server whose
     * connection dropped, and answering that as a client that left would hand a live caller an
     * empty {@code 2xx} for an operation that failed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Broken pipe", "Connection reset by peer"})
    void anIoErrorIsNeverAVanishedClientWhateverItsTextSays(String message) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IOException(message), new MockHttpServletRequest("GET", "/x"),
                response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error").doesNotContain(message);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void aDatabaseFailureWhoseRootCauseSaysConnectionResetIsAnInternalError() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Exception failure = new SQLException("could not read the ledger",
                new SocketException("Connection reset by peer"));

        handler.unexpected(failure, new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error");
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void aClientAbortOfTheContainerAnywhereInTheCauseChainIsAVanishedClient() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Exception failure = new IllegalStateException("could not write",
                new ClientAbortException(new IOException("Broken pipe")));

        handler.unexpected(failure, new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).as("never a 2xx for a response nobody received")
                .isEqualTo(ProblemExceptionHandler.CLIENT_CLOSED_REQUEST);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG);
        assertThat(logged.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void securityExceptionsAreRethrownForTheSecurityChainToAnswer() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        assertThatThrownBy(() -> handler.unexpected(new AccessDeniedException("no"), request,
                new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> handler.unexpected(new BadCredentialsException("no"), request,
                new MockHttpServletResponse())).isInstanceOf(BadCredentialsException.class);
        assertThat(logged.list).as("not an unforeseen failure: nothing logged as an error").isEmpty();
    }

    @Test
    void everyHandlerLeavesACommittedResponseAlone() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        handler.noResource(new NoResourceFoundException(HttpMethod.GET, "/x", "x"), request,
                response);
        handler.unreadableRequest(new IllegalStateException("x"), request, response);
        handler.domain(new TestDomainException("forbidden"), request, response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aLimitedRequestIs429WithItsWaitInWholeSecondsAndNothingIsLogged() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.tooManyRequests(new TooManyRequestsException(15),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeaders("Retry-After")).containsExactly("15");
        assertThat(response.getContentAsString()).contains("too-many-requests");
        assertThat(logged.list).as("a client over its limit is not an error").isEmpty();
    }

    @Test
    void aFullTableIs503WithNoWaitAndNoClue() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.capacityExceeded(new CapacityExceededException(),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaderNames()).as("no Retry-After: nobody can promise a time")
                .doesNotContain("Retry-After");
        assertThat(response.getContentAsString()).contains("capacity-exceeded");
        assertThat(logged.list).isEmpty();
    }

    @Test
    void theLimiterHandlersLeaveACommittedResponseAlone() throws IOException {
        MockHttpServletResponse limited = new MockHttpServletResponse();
        limited.setCommitted(true);
        MockHttpServletResponse full = new MockHttpServletResponse();
        full.setCommitted(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        handler.tooManyRequests(new TooManyRequestsException(15), request, limited);
        handler.capacityExceeded(new CapacityExceededException(), request, full);

        assertThat(limited.getContentAsString()).isEmpty();
        assertThat(limited.getHeaderNames()).doesNotContain("Retry-After");
        assertThat(limited.getStatus()).isEqualTo(200);
        assertThat(full.getContentAsString()).isEmpty();
        assertThat(full.getStatus()).isEqualTo(200);
    }

    /**
     * A {@code 429} of the framework (or of a controller that raises one) keeps the {@code
     * Retry-After} its author chose, as a {@code 405} keeps its {@code Allow}: the catalog text
     * tells the client to wait the time that header gives, so the header must be there. Only the
     * {@code 429} keeps it; a {@code 503} never carries one (nobody can promise a time).
     */
    @Test
    void aFrameworkTooManyRequestsKeepsItsRetryAfterAndNeverItsReason() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(statusWithRetryAfter(429, "30"), new MockHttpServletRequest("GET", "/x"),
                response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeaders("Retry-After")).containsExactly("30");
        assertThat(response.getContentAsString()).contains("too-many-requests")
                .doesNotContain("secret reason");
        assertThat(logged.list).as("a client over its limit is not an error").isEmpty();
    }

    @Test
    void aFrameworkTooManyRequestsWithoutARetryAfterAnswersWithoutOne() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatusCode.valueOf(429), "secret reason"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeaderNames()).doesNotContain("Retry-After");
    }

    @Test
    void aFrameworkServiceUnavailableNeverCarriesARetryAfterEvenIfItsAuthorSetOne()
            throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(statusWithRetryAfter(503, "30"), new MockHttpServletRequest("GET", "/x"),
                response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("capacity-exceeded");
        assertThat(response.getHeaderNames()).doesNotContain("Retry-After");
    }

    /** A {@code ResponseStatusException} that carries a header, the way its subclasses do. */
    private static org.springframework.web.server.ResponseStatusException statusWithRetryAfter(
            int status, String seconds) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatusCode.valueOf(status), "secret reason") {
            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                org.springframework.http.HttpHeaders headers =
                        new org.springframework.http.HttpHeaders();
                headers.set("Retry-After", seconds);
                return headers;
            }
        };
    }

    @Test
    void aDomainErrorWhoseCodeIsAServerErrorIsLoggedAtErrorAndAnUnknownCodeIsNamedInTheLog()
            throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse server = new MockHttpServletResponse();
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        MockHttpServletResponse client = new MockHttpServletResponse();

        handler.domain(new TestDomainException("internal-error"), request, server);
        handler.domain(new TestDomainException("payment-frozen"), request, unknown);
        handler.domain(new TestDomainException("forbidden"), request, client);

        assertThat(server.getStatus()).isEqualTo(500);
        assertThat(unknown.getStatus()).isEqualTo(500);
        assertThat(unknown.getContentAsString()).doesNotContain("payment-frozen");
        assertThat(client.getStatus()).isEqualTo(403);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.ERROR, Level.ERROR);
        assertThat(logged.list.get(1).getFormattedMessage()).contains("payment-frozen");
    }

    /** A business error with a code of the caller's choosing. */
    private static final class TestDomainException extends DomainException {
        TestDomainException(String code) {
            super(code, "internal: row 8841");
        }
    }

    @Test
    void anIoErrorThatIsNotAVanishedClientIsAnUnforeseenFailure() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IOException("No space left on device"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error")
                .doesNotContain("space");
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void theAsyncRequestNotUsableExceptionOfSpringIsAVanishedClient() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new AsyncRequestNotUsableException("ServletOutputStream failed"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(ProblemExceptionHandler.CLIENT_CLOSED_REQUEST);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(List.copyOf(logged.list)).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG);
    }

    /** A service-style method whose parameter carries a constraint, validated by hand below. */
    static final class Greeter {
        void greet(@NotBlank String who) {
        }

        @NotBlank
        String name() {
            return "";
        }
    }

    /** A body with a map and a list, to see what a path does with the keys and the indexes. */
    record Holder(@Valid Map<String, Item> props, List<@NotBlank String> items) {
    }

    record Item(@NotBlank String campo) {
    }

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void aMapKeyAnAnIndexAndTheSyntheticNodesNeverReachTheFieldPath() throws Exception {
        String key = "VALOR-SENSIBLE-123";
        Holder holder = new Holder(Map.of(key, new Item(""), "x].secret[y", new Item(" ")),
                List.of("ok", " "));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidConstraints(new ConstraintViolationException(VALIDATOR.validate(holder)),
                new MockHttpServletRequest("GET", "/x"), response);

        JsonNode errors = JSON.readTree(response.getContentAsString()).get("errors");
        List<String> fields = new ArrayList<>();
        errors.forEach(error -> fields.add(error.get("field").asString()));
        assertThat(fields).containsExactlyInAnyOrder("props[].campo", "props[].campo", "items[]");
        assertThat(response.getContentAsString()).doesNotContain("VALOR").doesNotContain("secret")
                .doesNotContain("<");
    }

    @Test
    void aViolationOfAReturnValueIsAServerDefectAnsweredAs500AndLoggedAtError() throws Exception {
        Method name = Greeter.class.getDeclaredMethod("name");
        Set<ConstraintViolation<Greeter>> violations = VALIDATOR.forExecutables()
                .validateReturnValue(new Greeter(), name, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidConstraints(new ConstraintViolationException(violations),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(violations).hasSize(1);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error")
                .doesNotContain("errors").doesNotContain("name");
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void aViolationWithAnEmptyPathIsAnEmptyFieldAndNotACrash() throws Exception {
        NotBlank annotation = Greeter.class.getDeclaredMethod("greet", String.class)
                .getParameters()[0].getAnnotation(NotBlank.class);
        ConstraintDescriptor<?> descriptor = (ConstraintDescriptor<?>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ConstraintDescriptor.class},
                (proxy, method, args) -> "getAnnotation".equals(method.getName()) ? annotation
                        : null);
        Path empty = () -> Collections.emptyIterator();
        ConstraintViolation<?> violation = (ConstraintViolation<?>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {ConstraintViolation.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPropertyPath" -> empty;
                    case "getConstraintDescriptor" -> descriptor;
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidConstraints(new ConstraintViolationException(Set.of(violation)),
                new MockHttpServletRequest("GET", "/x"), response);

        JsonNode error = JSON.readTree(response.getContentAsString()).get("errors").get(0);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(error.get("field").asString()).isEmpty();
        assertThat(error.get("reason").asString()).isEqualTo("not-blank");
    }

    @Test
    void aBindExceptionIsTranslatedFromItsBindingResult() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "body");
        result.addError(new FieldError("body", "name", "a message that must not be repeated"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidBinding(new BindException(result), new MockHttpServletRequest("GET", "/x"),
                response);

        JsonNode errors = JSON.readTree(response.getContentAsString()).get("errors");
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).get("field").asString()).isEqualTo("name");
        assertThat(response.getContentAsString()).doesNotContain("repeated");
    }

    @Test
    void aMethodValidationExceptionIsAValidationFailureForAParameterAndAServerDefectForAReturn()
            throws Exception {
        Method greet = Greeter.class.getDeclaredMethod("greet", String.class);
        ParameterValidationResult parameter = new ParameterValidationResult(
                new MethodParameter(greet, 0), " ",
                List.of(new DefaultMessageSourceResolvable(
                        new String[] {"NotBlank.greet.who", "NotBlank"}, "must not be repeated")),
                null, null, null, (error, type) -> null);
        MockHttpServletResponse parameters = new MockHttpServletResponse();
        MockHttpServletResponse returned = new MockHttpServletResponse();

        handler.invalidMethod(new MethodValidationException(
                MethodValidationResult.create(new Greeter(), greet, List.of(parameter))),
                new MockHttpServletRequest("GET", "/x"), parameters);
        handler.invalidMethod(new MethodValidationException(new ReturnValueResult(greet)),
                new MockHttpServletRequest("GET", "/x"), returned);

        JsonNode error = JSON.readTree(parameters.getContentAsString()).get("errors").get(0);
        assertThat(parameters.getStatus()).isEqualTo(400);
        assertThat(error.get("reason").asString()).isEqualTo("not-blank");
        assertThat(parameters.getContentAsString()).doesNotContain("repeated");
        assertThat(returned.getStatus()).isEqualTo(500);
        assertThat(returned.getContentAsString()).contains("internal-error")
                .doesNotContain("errors");
    }

    /** A method validation result that reports the return value as the invalid part. */
    private record ReturnValueResult(Method method) implements MethodValidationResult {
        @Override
        public Object getTarget() {
            return new Greeter();
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public boolean isForReturnValue() {
            return true;
        }

        @Override
        public List<ParameterValidationResult> getParameterValidationResults() {
            return List.of();
        }

        @Override
        public List<MessageSourceResolvable> getCrossParameterValidationResults() {
            return List.of();
        }
    }

    @Test
    void aViolationOfAMethodParameterNamesTheParameterAndNotTheMethod() throws Exception {
        Method greet = Greeter.class.getDeclaredMethod("greet", String.class);
        Set<ConstraintViolation<Greeter>> violations = Validation.buildDefaultValidatorFactory()
                .getValidator().forExecutables()
                .validateParameters(new Greeter(), greet, new Object[] {" "});
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidConstraints(new ConstraintViolationException(violations),
                new MockHttpServletRequest("GET", "/x"), response);

        JsonNode error = JSON.readTree(response.getContentAsString()).get("errors").get(0);
        assertThat(error.get("field").asString()).as("not greet.arg0").isIn("", "who");
        assertThat(error.get("reason").asString()).isEqualTo("not-blank");
        assertThat(response.getContentAsString()).doesNotContain("greet");
    }

    @Test
    void anErrorOfTheWholeObjectHasAnEmptyFieldAndAnErrorWithoutCodesIsJustInvalid()
            throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "body");
        result.addError(new ObjectError("body", "a message that must not be repeated"));
        MethodParameter parameter = new MethodParameter(Object.class.getMethod("toString"), -1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.invalidBody(new MethodArgumentNotValidException(parameter, result),
                new MockHttpServletRequest("GET", "/x"), response);

        JsonNode errors = JSON.readTree(response.getContentAsString()).get("errors");
        assertThat(errors.size()).isEqualTo(1);
        assertThat(errors.get(0).get("field").asString()).isEmpty();
        assertThat(errors.get(0).get("reason").asString()).isEqualTo("invalid");
        assertThat(response.getContentAsString()).doesNotContain("repeated");
    }

    /**
     * Both validation exceptions of Spring MVC are also an {@link ErrorResponse}, which the generic
     * branch answers without {@code errors}. Spring picks the handler closest to the exception, so
     * the specific handler wins; this pins that it does, and that the precondition that makes the
     * ordering matter holds.
     */
    @Test
    void theValidationHandlersWinOverTheGenericErrorResponseBranch() {
        ExceptionHandlerMethodResolver resolver =
                new ExceptionHandlerMethodResolver(ProblemExceptionHandler.class);

        assertThat(ErrorResponse.class).isAssignableFrom(MethodArgumentNotValidException.class);
        assertThat(ErrorResponse.class).isAssignableFrom(HandlerMethodValidationException.class);
        assertThat(resolver.resolveMethodByExceptionType(MethodArgumentNotValidException.class))
                .extracting(Method::getName).isEqualTo("invalidBody");
        assertThat(resolver.resolveMethodByExceptionType(HandlerMethodValidationException.class))
                .extracting(Method::getName).isEqualTo("invalidParameters");
        assertThat(resolver.resolveMethodByExceptionType(ConstraintViolationException.class))
                .extracting(Method::getName).isEqualTo("invalidConstraints");
        assertThat(resolver.resolveMethodByExceptionType(BindException.class))
                .extracting(Method::getName).isEqualTo("invalidBinding");
        assertThat(resolver.resolveMethodByExceptionType(MethodValidationException.class))
                .extracting(Method::getName).isEqualTo("invalidMethod");
        assertThat(resolver.resolveMethodByExceptionType(HttpMediaTypeNotAcceptableException.class))
                .as("non-vacuous: any other ErrorResponse still falls to the generic handler")
                .extracting(Method::getName).isEqualTo("unexpected");
    }
}
