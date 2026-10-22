package com.confia.shared.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RateLimitDecision;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.harness.HarnessProcess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Session-endpoints-cookie-and-csrf design.md, decision 7: {@link RateLimitFailures} reports a
 * failure of the client of the current request to the limiter of the named policy, so a controller
 * never names the {@link RateLimiter} port nor reads the request origin itself. It reports to that
 * policy only, and to nothing when the request has no origin or no address (the interceptor would
 * already have answered {@code 503}).
 */
class RateLimitFailuresTest {

    private static final ClientAddress CLIENT = ClientAddress.parseLiteral("203.0.113.90");

    /** A limiter that remembers the failures reported to it and admits everything. */
    private static final class RecordingLimiter implements RateLimiter {

        private final List<ClientAddress> failures = new ArrayList<>();

        @Override
        public RateLimitDecision tryAcquire(ClientAddress client) {
            throw new AssertionError("recording a failure never asks for admission");
        }

        @Override
        public void recordFailure(ClientAddress client) {
            failures.add(client);
        }
    }

    private static void within(ClientAddress address, Runnable action) {
        ScopedValue.where(RequestOrigin.CURRENT, new RequestOrigin(UUID.randomUUID(), address,
                "agent")).run(action);
    }

    private final RecordingLimiter login = new RecordingLimiter();
    private final RecordingLimiter mfa = new RecordingLimiter();
    private final RecordingLimiter session = new RecordingLimiter();
    private final RateLimitFailures failures = new RateLimitFailures(new RateLimiterRegistry(
            Map.of("admin-login", login, "admin-mfa", mfa, "admin-session", session)));

    @Test
    void aFailureGoesToTheLimiterOfThePolicyForTheClientOfTheRequestAndToNoOtherPolicy() {
        within(CLIENT, () -> failures.record("admin-mfa"));

        assertThat(mfa.failures).containsExactly(CLIENT);
        assertThat(login.failures).isEmpty();
        assertThat(session.failures).isEmpty();
    }

    @Test
    void eachPolicyReceivesItsOwnFailures() {
        within(CLIENT, () -> {
            failures.record("admin-login");
            failures.record("admin-session");
            failures.record("admin-session");
        });

        assertThat(login.failures).hasSize(1);
        assertThat(session.failures).hasSize(2);
        assertThat(mfa.failures).isEmpty();
    }

    @Test
    void outsideARequestNothingIsRecorded() {
        failures.record("admin-login");

        assertThat(login.failures).isEmpty();
    }

    @Test
    void aRequestWithoutAnAddressRecordsNothing() {
        within(null, () -> failures.record("admin-login"));

        assertThat(login.failures).isEmpty();
    }

    @Test
    void aPolicyNobodyRegisteredIsAProgrammingErrorAndNeverASilentNoOp() {
        within(CLIENT, () -> assertThatThrownBy(() -> failures.record("admin-otro"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("admin-otro"));
    }

    @Test
    void tenFailuresOfTheSecondFactorRestrictOnlyTheSecondFactorOfThatClient() {
        try (HarnessProcess process = HarnessProcess.start(
                "confia.web.trusted-proxies=127.0.0.1,::1")) {
            RateLimitFailures real = process.bean(RateLimitFailures.class);
            within(ClientAddress.parseLiteral("203.0.113.91"), () -> {
                for (int i = 0; i < 10; i++) {
                    real.record("admin-mfa");
                }
            });

            // Layer 2 admits one attempt a minute to a restricted client: the first goes through
            // and the second is the restriction.
            assertThat(process.get("/test/limited-mfa", "X-Forwarded-For", "203.0.113.91")
                    .statusCode()).as("the one attempt a restricted client has").isEqualTo(200);
            assertThat(process.get("/test/limited-mfa", "X-Forwarded-For", "203.0.113.91")
                    .statusCode()).as("the second-factor policy restricts the client").isEqualTo(429);
            for (int i = 0; i < 3; i++) {
                assertThat(process.get("/test/limited", "X-Forwarded-For", "203.0.113.91")
                        .statusCode()).as("the login policy does not").isEqualTo(200);
                assertThat(process.get("/test/limited-session", "X-Forwarded-For", "203.0.113.91")
                        .statusCode()).as("nor does the session policy").isEqualTo(200);
            }
            assertThat(process.get("/test/limited-mfa", "X-Forwarded-For", "203.0.113.92")
                    .statusCode()).as("another client is not restricted").isEqualTo(200);
        }
    }
}
