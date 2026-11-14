package com.confia.shared.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.ClientKey;
import com.confia.shared.security.RateLimitDecision;
import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.Limited;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.problem.CapacityExceededException;
import jakarta.servlet.DispatcherType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * The second key dimension of the limiter: a policy that counts by the authenticated account in addition to the
 * policy that counts by address (docs/03-seguridad.md section 10). The account is read from the verified
 * principal of the request and nowhere else; a request without one is refused as lack of capacity.
 */
class RateLimitInterceptorAccountTest {

    private static final ClientAddress CLIENT = ClientAddress.parseLiteral("203.0.113.9");
    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    static final class Controller {
        @RateLimited(policy = "by-address")
        @RateLimited(policy = "by-account", key = RateLimitKey.ACCOUNT)
        void both() {
        }

        @RateLimited(policy = "by-account", key = RateLimitKey.ACCOUNT)
        void accountOnly() {
        }

        @RateLimited(policy = "by-address")
        void addressOnly() {
        }
    }

    private record Signal(String policy, CapacityReason reason) {
    }

    private final List<String> asked = new ArrayList<>();
    private final List<ClientKey> accountKeys = new ArrayList<>();
    private final List<Signal> reported = new ArrayList<>();
    private final RateLimitMetrics metrics = (policy, reason) -> reported.add(new Signal(policy, reason));

    private RateLimiter addressLimiter(Supplier<RateLimitDecision> answer) {
        return new RateLimiter() {
            @Override
            public RateLimitDecision tryAcquire(ClientAddress client) {
                asked.add("address");
                return answer.get();
            }

            @Override
            public void recordFailure(ClientAddress client) {
                throw new AssertionError("no failure is recorded");
            }
        };
    }

    private RateLimiter accountLimiter(Supplier<RateLimitDecision> answer) {
        return new RateLimiter() {
            @Override
            public RateLimitDecision tryAcquire(ClientAddress client) {
                throw new AssertionError("the account policy never counts by address");
            }

            @Override
            public RateLimitDecision tryAcquire(ClientKey key) {
                asked.add("account");
                accountKeys.add(key);
                return answer.get();
            }

            @Override
            public void recordFailure(ClientAddress client) {
                throw new AssertionError("no failure is recorded");
            }
        };
    }

    private RateLimitInterceptor interceptor(RateLimiter byAddress, RateLimiter byAccount) {
        return new RateLimitInterceptor(new RateLimiterRegistry(Map.of("by-address", byAddress,
                "by-account", byAccount)), metrics);
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        Method method = Controller.class.getDeclaredMethod(name);
        return new HandlerMethod(new Controller(), method);
    }

    private static AuthenticatedActor actor(UUID account) {
        return new AuthenticatedActor(account, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private static boolean preHandle(RateLimitInterceptor interceptor, HandlerMethod handler, AuthenticatedActor actor,
            MockHttpServletRequest request) throws Exception {
        request.setDispatcherType(DispatcherType.REQUEST);
        RequestOrigin origin = new RequestOrigin(UUID.randomUUID(), CLIENT, "agent");
        ScopedValue.Carrier carrier = ScopedValue.where(RequestOrigin.CURRENT, origin);
        if (actor != null) {
            carrier = carrier.where(AuthenticatedActor.CURRENT, actor);
        }
        return carrier.call(() -> interceptor.preHandle(request, new MockHttpServletResponse(), handler));
    }

    private static boolean preHandle(RateLimitInterceptor interceptor, HandlerMethod handler, AuthenticatedActor actor)
            throws Exception {
        return preHandle(interceptor, handler, actor, new MockHttpServletRequest("GET", "/test"));
    }

    @Test
    void theAddressLayerIsAskedFirstThenTheAccountLayerWithTheKeyOfTheAuthenticatedAccount() throws Exception {
        UUID account = UUID.randomUUID();
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new), accountLimiter(Admitted::new));

        assertThat(preHandle(interceptor, handler("both"), actor(account))).isTrue();

        assertThat(asked).containsExactly("address", "account");
        assertThat(accountKeys).containsExactly(ClientKey.ofAccount(account));
        assertThat(reported).isEmpty();
    }

    @Test
    void aRequestRefusedByTheAddressLayerDoesNotSpendTheQuotaOfItsAccount() throws Exception {
        RateLimitInterceptor interceptor = interceptor(addressLimiter(() -> new Limited(Duration.ofSeconds(7))),
                accountLimiter(Admitted::new));

        assertThatThrownBy(() -> preHandle(interceptor, handler("both"), actor(UUID.randomUUID())))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(7));

        assertThat(asked).containsExactly("address");
    }

    @Test
    void anAccountOverItsQuotaIsRefusedWithItsWait() throws Exception {
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new),
                accountLimiter(() -> new Limited(Duration.ofSeconds(31))));

        assertThatThrownBy(() -> preHandle(interceptor, handler("both"), actor(UUID.randomUUID())))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(31));
        assertThat(asked).containsExactly("address", "account");
    }

    @Test
    void theAccountComesFromThePrincipalNeverFromAHeaderOrAParameter() throws Exception {
        UUID real = UUID.randomUUID();
        UUID forged = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.addHeader("X-Account-Id", forged.toString());
        request.addHeader("X-Forwarded-User", forged.toString());
        request.setParameter("accountId", forged.toString());
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new), accountLimiter(Admitted::new));

        preHandle(interceptor, handler("accountOnly"), actor(real), request);

        assertThat(accountKeys).containsExactly(ClientKey.ofAccount(real));
        assertThat(accountKeys).doesNotContain(ClientKey.ofAccount(forged));
    }

    @Test
    void aRequestWithoutAnAuthenticatedAccountIsRefusedAsLackOfCapacityAndNeverAdmitted() throws Exception {
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new), accountLimiter(Admitted::new));

        assertThatThrownBy(() -> preHandle(interceptor, handler("accountOnly"), null))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(asked).as("the limiter was never asked").isEmpty();
        assertThat(reported).containsExactly(new Signal("by-account", CapacityReason.NO_ACCOUNT));
    }

    @Test
    void aLimiterThatCountsAddressesOnlyCannotServeAnAccountPolicyAndFailsClosed() throws Exception {
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new), addressLimiter(Admitted::new));

        assertThatThrownBy(() -> preHandle(interceptor, handler("accountOnly"), actor(UUID.randomUUID())))
                .isInstanceOf(CapacityExceededException.class);

        assertThat(reported).containsExactly(new Signal("by-account", CapacityReason.LIMITER_FAILURE));
    }

    @Test
    void aMethodWithOnlyTheAddressPolicyBehavesAsBefore() throws Exception {
        RateLimitInterceptor interceptor = interceptor(addressLimiter(Admitted::new), accountLimiter(Admitted::new));

        assertThat(preHandle(interceptor, handler("addressOnly"), null)).isTrue();

        assertThat(asked).containsExactly("address");
    }

    @Test
    void anUnknownPolicyOnARepeatedAnnotationIsReportedByTheStartCheck() throws Exception {
        RateLimiterRegistry onlyAddress = new RateLimiterRegistry(Map.of("by-address", addressLimiter(Admitted::new)));

        List<String> problems = RateLimitPolicyCheck.unknownPolicies(List.of(handler("both"), handler("addressOnly")),
                onlyAddress);

        assertThat(problems).singleElement().asString().contains("by-account").contains("Controller#both");
    }

    @Test
    void anAccountKeyIsDeterministicDistinctPerAccountAndNeverEqualToAnAddressKey() {
        UUID account = UUID.randomUUID();

        assertThat(ClientKey.ofAccount(account)).isEqualTo(ClientKey.ofAccount(account))
                .hasSameHashCodeAs(ClientKey.ofAccount(account));
        assertThat(ClientKey.ofAccount(account)).isNotEqualTo(ClientKey.ofAccount(UUID.randomUUID()));
        assertThat(ClientKey.ofAccount(account)).isNotEqualTo(CLIENT.rateLimitKey());
    }
}
