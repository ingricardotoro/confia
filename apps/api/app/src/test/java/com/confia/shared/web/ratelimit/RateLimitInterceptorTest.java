package com.confia.shared.web.ratelimit;

import static com.confia.shared.web.ratelimit.CapacityReason.LIMITER_FAILURE;
import static com.confia.shared.web.ratelimit.CapacityReason.NO_ADDRESS;
import static com.confia.shared.web.ratelimit.CapacityReason.NO_ORIGIN;
import static com.confia.shared.web.ratelimit.CapacityReason.TABLE_FULL;
import static com.confia.shared.web.ratelimit.CapacityReason.UNKNOWN_POLICY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RateLimitDecision;
import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.problem.CapacityExceededException;
import jakarta.servlet.DispatcherType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Specs/web-edge, requirements "El rechazo por límite responde {@code 429}...", "El limitador se
 * ejecuta antes del caso de uso" and "La tabla acotada del limitador falla cerrada" (design.md,
 * decision 17), at the level of the interceptor: what it decides for each answer of the limiter,
 * what it reports to the metrics port and with which cause, and that everything it cannot decide is
 * a refusal. The same behavior over the real chain is in {@code RateLimitEdgeTest} (3.2c).
 *
 * <p>A {@code HEAD} request to a limited {@code GET} handler is not tested here: that Spring maps
 * {@code HEAD} to the handler of {@code GET} is a fact of the real handler mapping, and a unit test
 * that hands the interceptor a {@code HEAD} request would only assume it. It is proven over the
 * real chain in 3.2c (dated note of tasks.md).
 */
class RateLimitInterceptorTest {

    private static final String POLICY = "admin-login";
    private static final ClientAddress CLIENT = ClientAddress.parseLiteral("203.0.113.9");

    /** One signal that reached the metrics port. */
    private record Signal(String policy, CapacityReason reason) {
    }

    /** The handler of the controller under test: one limited method and one that is not. */
    static final class Controller {
        @RateLimited(policy = POLICY)
        void limited() {
        }

        @RateLimited(policy = "no-such-policy")
        void unknownPolicy() {
        }

        void free() {
        }
    }

    private final List<ClientAddress> asked = new ArrayList<>();
    private final List<Signal> reported = new ArrayList<>();
    private final RateLimitMetrics metrics = (policy, reason) -> reported.add(
            new Signal(policy, reason));
    private final AtomicLong nanos = new AtomicLong(-987_654_321_000L);

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RateLimitInterceptor.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private RateLimitInterceptor interceptorAnswering(Supplier<RateLimitDecision> answer) {
        return interceptorAnswering(answer, metrics);
    }

    private RateLimitInterceptor interceptorAnswering(Supplier<RateLimitDecision> answer,
            RateLimitMetrics withMetrics) {
        RateLimiter limiter = new RateLimiter() {
            @Override
            public RateLimitDecision tryAcquire(ClientAddress client) {
                asked.add(client);
                return answer.get();
            }

            @Override
            public void recordFailure(ClientAddress client) {
                throw new AssertionError("a rejected or admitted request records no failure");
            }
        };
        return new RateLimitInterceptor(new RateLimiterRegistry(Map.of(POLICY, limiter)),
                withMetrics, nanos::get);
    }

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        Method target = Controller.class.getDeclaredMethod(method);
        return new HandlerMethod(new Controller(), target);
    }

    private static RequestOrigin origin(ClientAddress client) {
        return new RequestOrigin(UUID.randomUUID(), client, "agent");
    }

    private boolean preHandle(RateLimitInterceptor interceptor, Object handler,
            RequestOrigin origin) {
        return preHandle(interceptor, handler, origin, DispatcherType.REQUEST);
    }

    private boolean preHandle(RateLimitInterceptor interceptor, Object handler,
            RequestOrigin origin, DispatcherType dispatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/test/limited");
        request.setDispatcherType(dispatch);
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (origin == null) {
            return interceptor.preHandle(request, response, handler);
        }
        return ScopedValue.where(RequestOrigin.CURRENT, origin)
                .call(() -> interceptor.preHandle(request, response, handler));
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            fields.put(pair.key, pair.value);
        }
        return fields;
    }

    @Test
    void anAdmittedRequestProceedsAndTheLimiterSawTheClientOfTheRequest() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(Admitted::new);

        assertThat(preHandle(interceptor, handler("limited"), origin(CLIENT))).isTrue();

        assertThat(asked).containsExactly(CLIENT);
        assertThat(reported).as("an admission is not a capacity problem").isEmpty();
    }

    @Test
    void aLimitedRequestIsRefusedWithItsWaitRoundedUpToWholeSeconds() throws Exception {
        RateLimitInterceptor shortWait =
                interceptorAnswering(() -> new Limited(Duration.ofMillis(200)));
        RateLimitInterceptor layerOneWait =
                interceptorAnswering(() -> new Limited(Duration.ofSeconds(15)));

        assertThatThrownBy(() -> preHandle(shortWait, handler("limited"), origin(CLIENT)))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
        assertThatThrownBy(() -> preHandle(layerOneWait, handler("limited"), origin(CLIENT)))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(15));
        assertThat(reported).as("a client over its limit is not a capacity problem").isEmpty();
    }

    @Test
    void aFullTableIsRefusedAsLackOfCapacityAndReportedOnceWithThePolicyAndTheCause()
            throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(CapacityExhausted::new);

        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(reported).containsExactly(new Signal(POLICY, TABLE_FULL));
        assertThat(logged.list).as("a full table is the attack the signal reports, not an error")
                .isEmpty();
    }

    @Test
    void aRequestWithNoOriginIsRefusedAsLackOfCapacityWithoutAskingTheLimiter() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(Admitted::new);

        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), null))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(asked).as("nothing to key on: the limiter is never asked").isEmpty();
        assertThat(reported).containsExactly(new Signal(POLICY, NO_ORIGIN));
    }

    @Test
    void aRequestWhoseOriginHasNoAddressIsRefusedAsLackOfCapacity() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(Admitted::new);

        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(null)))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(asked).isEmpty();
        assertThat(reported).containsExactly(new Signal(POLICY, NO_ADDRESS));
    }

    @Test
    void anUnexpectedFailureOfTheLimiterIsARefusalAndNeverAnAdmission() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(() -> {
            throw new IllegalStateException("the table is corrupt");
        });

        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class)
                .hasNoCause()
                .hasMessageNotContaining("corrupt");

        assertThat(reported).containsExactly(new Signal(POLICY, LIMITER_FAILURE));
    }

    @Test
    void anUnexpectedFailureIsLoggedAtErrorWithTheClassOfTheExceptionAndNothingElse()
            throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(() -> {
            throw new IllegalStateException("the table is corrupt for 203.0.113.9");
        });

        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("rate limiter failed unexpectedly");
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getThrowableProxy()).as("no stack trace: its message may carry anything")
                .isNull();
        assertThat(fieldsOf(event)).containsExactly(Map.entry("policy", POLICY),
                Map.entry("exception", IllegalStateException.class.getName()));
        assertThat(event.toString()).doesNotContain("corrupt").doesNotContain("203.0.113.9");
    }

    @Test
    void theErrorLogIsBoundedToOneEventPerSecond() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(() -> {
            throw new IllegalStateException("boom");
        });

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                    .isInstanceOf(CapacityExceededException.class);
        }
        assertThat(logged.list).as("a flood of failures costs one line").hasSize(1);
        assertThat(reported).as("the refusals are all signaled to the metrics port").hasSize(50);

        nanos.addAndGet(Duration.ofMillis(999).toNanos());
        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);
        assertThat(logged.list).as("999 ms later is still the same second").hasSize(1);

        nanos.addAndGet(Duration.ofMillis(1).toNanos());
        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);
        assertThat(logged.list).as("exactly one second later the next one is written").hasSize(2);
    }

    @Test
    void aPolicyTheRegistryDoesNotHoldIsARefusalAtRunTime() throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(Admitted::new);

        assertThatThrownBy(() -> preHandle(interceptor, handler("unknownPolicy"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(asked).isEmpty();
        assertThat(reported).containsExactly(new Signal("no-such-policy", UNKNOWN_POLICY));
    }

    @Test
    void aFailingMetricsAdapterNeverTurnsARefusalIntoAServerError() throws Exception {
        RateLimitMetrics failing = (policy, reason) -> {
            throw new IllegalStateException("the registry is gone for 203.0.113.9");
        };
        RateLimitInterceptor full = interceptorAnswering(CapacityExhausted::new, failing);
        RateLimitInterceptor limited =
                interceptorAnswering(() -> new Limited(Duration.ofSeconds(3)), failing);

        assertThatThrownBy(() -> preHandle(full, handler("limited"), origin(CLIENT)))
                .as("the answer stays 503, never the 500 of the exception of the adapter")
                .isInstanceOf(CapacityExceededException.class).hasNoCause();
        assertThatThrownBy(() -> preHandle(full, handler("unknownPolicy"), origin(CLIENT)))
                .isInstanceOf(CapacityExceededException.class);
        assertThatThrownBy(() -> preHandle(limited, handler("limited"), origin(CLIENT)))
                .as("a limited request is still a 429")
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(3));

        assertThat(logged.list).as("the defect of the adapter is not silent").hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("rate limit metrics failed");
        assertThat(fieldsOf(event)).containsExactly(Map.entry("policy", POLICY),
                Map.entry("exception", IllegalStateException.class.getName()));
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.toString()).doesNotContain("registry").doesNotContain("203.0.113.9");
    }

    @Test
    void anAsyncDispatchIsNotEvaluatedAgainBecauseTheRequestDispatchAlreadyWas()
            throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(CapacityExhausted::new);

        assertThat(preHandle(interceptor, handler("limited"), origin(CLIENT),
                DispatcherType.ASYNC)).isTrue();
        assertThat(preHandle(interceptor, handler("limited"), null, DispatcherType.ASYNC))
                .as("not even a request with no origin is refused on the second dispatch").isTrue();

        assertThat(asked).as("the limiter is not asked twice for one request").isEmpty();
        assertThat(reported).isEmpty();
        assertThatThrownBy(() -> preHandle(interceptor, handler("limited"), origin(CLIENT),
                DispatcherType.REQUEST)).as("the request dispatch itself is still evaluated")
                .isInstanceOf(CapacityExceededException.class);
    }

    @Test
    void aHandlerWithoutTheAnnotationIsNeverLimitedAndNeitherIsAnythingThatIsNotAHandlerMethod()
            throws Exception {
        RateLimitInterceptor interceptor = interceptorAnswering(CapacityExhausted::new);

        assertThat(preHandle(interceptor, handler("free"), origin(CLIENT))).isTrue();
        assertThat(preHandle(interceptor, new Object(), origin(CLIENT))).isTrue();
        assertThat(preHandle(interceptor, handler("free"), null)).isTrue();

        assertThat(asked).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    void theStartCheckNamesEveryUnknownPolicyAndTheMethodThatDeclaresIt() throws Exception {
        RateLimiterRegistry registry = new RateLimiterRegistry(
                Map.of(POLICY, new RateLimiter() {
                    @Override
                    public RateLimitDecision tryAcquire(ClientAddress client) {
                        return new Admitted();
                    }

                    @Override
                    public void recordFailure(ClientAddress client) {
                    }
                }));

        List<String> problems = RateLimitPolicyCheck.unknownPolicies(
                List.of(handler("limited"), handler("free"), handler("unknownPolicy")), registry);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("no-such-policy").contains("Controller#unknownPolicy")
                .contains("known: [" + POLICY + "]");
        assertThat(RateLimitPolicyCheck.unknownPolicies(
                List.of(handler("limited"), handler("free")), registry))
                .as("a known policy and a method with none are fine").isEmpty();
    }
}
