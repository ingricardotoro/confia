package com.confia.shared.web.ratelimit;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.observability.metrics.LogRateLimitMetrics;
import com.confia.shared.security.RateLimitPolicy;
import com.confia.shared.web.harness.Calls;
import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, requirements "El rechazo por límite responde {@code 429}...", "El limitador se
 * ejecuta antes del caso de uso", "La tabla acotada del limitador falla cerrada al llenarse" and
 * "Los límites viven en configuración y se validan al arrancar" (web-edge-foundations design.md,
 * decision 17), through the real security chain and the real servlet container. A test controller
 * annotated {@code @RateLimited(policy = "admin-login")} and a stand-in use case count their
 * invocations. Each client is a different {@code X-Forwarded-For} address, which the harness
 * believes because the loopback is its trusted proxy.
 */
class RateLimitEdgeTest {

    private static final String PREFIX = "confia.web.rate-limit.admin-login.";
    private static final String TRUST_LOOPBACK = "confia.web.trusted-proxies=127.0.0.1,::1";
    private static final String FORWARDED = "X-Forwarded-For";

    private final ListAppender<ILoggingEvent> metricEvents = new ListAppender<>();
    private final Logger metricLogger = (Logger) LoggerFactory.getLogger(LogRateLimitMetrics.class);

    /**
     * Called after the process started: starting a Spring Boot application initializes the logging
     * system again, which drops every appender attached to a logger before it.
     */
    private void captureTheCapacitySignal() {
        metricEvents.start();
        metricLogger.addAppender(metricEvents);
    }

    @AfterEach
    void releaseTheCapacitySignal() {
        metricLogger.detachAppender(metricEvents);
    }

    private static HarnessProcess start(String... settings) {
        List<String> all = new ArrayList<>(List.of(TRUST_LOOPBACK));
        all.addAll(List.of(settings));
        return HarnessProcess.start(all.toArray(String[]::new));
    }

    private static HttpResponse<String> asClient(HarnessProcess process, String address) {
        return process.get("/test/limited", FORWARDED, address);
    }

    private static String headerNamesOf(HttpResponse<String> response) {
        return response.headers().map().keySet().stream().map(String::toLowerCase)
                .collect(Collectors.joining(","));
    }

    @Test
    void theRequestAfterTheLimitIs429WithAWholeSecondWaitAndNothingAboutTheQuota() {
        try (HarnessProcess process = start(PREFIX + "request-limit=3")) {
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                statuses.add(asClient(process, "203.0.113.10").statusCode());
            }
            HttpResponse<String> limited = asClient(process, "203.0.113.10");

            assertThat(statuses).as("the limit configured as 3 admits three").containsExactly(200,
                    200, 200);
            JsonNode problem = assertProblem(limited, 429, "too-many-requests");
            assertThat(limited.headers().allValues("Retry-After")).hasSize(1);
            String wait = limited.headers().firstValue("Retry-After").orElseThrow();
            assertThat(wait).as("whole seconds, never a date or a fraction").matches("[0-9]+");
            assertThat(Long.parseLong(wait)).as("never below one, never past the window")
                    .isBetween(1L, 60L);
            assertThat(headerNamesOf(limited)).doesNotContain("ratelimit").doesNotContain("limit")
                    .doesNotContain("remaining").doesNotContain("quota");
            assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title",
                    "status", "detail", "instance", "traceId");
            assertThat(problem.get("title").asString() + problem.get("detail").asString())
                    .as("no number of the limit or of what is left").doesNotContainPattern("[0-9]");
            assertBaseSecurityHeaders(limited);
        }
    }

    @Test
    void aRejectedRequestReachesNeitherTheControllerNorTheUseCase() {
        try (HarnessProcess process = start(PREFIX + "request-limit=2")) {
            Calls calls = process.calls();

            asClient(process, "203.0.113.20");
            assertThat(calls.limitedInvocations()).as("non-vacuous: an admitted request does run")
                    .isEqualTo(1);
            asClient(process, "203.0.113.20");
            HttpResponse<String> rejected = asClient(process, "203.0.113.20");

            assertThat(rejected.statusCode()).isEqualTo(429);
            assertThat(calls.limitedInvocations()).isEqualTo(2);
            assertThat(calls.useCaseInvocations()).isEqualTo(2);
        }
    }

    @Test
    void oneClientsLimitDoesNotLimitAnotherAndARouteWithoutTheAnnotationIsNeverLimited() {
        try (HarnessProcess process = start(PREFIX + "request-limit=1")) {
            assertThat(asClient(process, "203.0.113.30").statusCode()).isEqualTo(200);
            assertThat(asClient(process, "203.0.113.30").statusCode()).isEqualTo(429);

            assertThat(asClient(process, "203.0.113.31").statusCode()).isEqualTo(200);
            for (int i = 0; i < 3; i++) {
                assertThat(process.get("/test/unlimited", FORWARDED, "203.0.113.30").statusCode())
                        .isEqualTo(200);
            }
        }
    }

    @Test
    void aFullTableAnswers503WithNoWaitAndNoClueOfWhoCausedItAndItsLiveEntriesKeepWorking() {
        try (HarnessProcess process = start(PREFIX + "max-entries=1")) {
            assertThat(asClient(process, "203.0.113.40").statusCode()).isEqualTo(200);

            HttpResponse<String> full = asClient(process, "203.0.113.41");

            assertProblem(full, 503, "capacity-exceeded");
            assertThat(full.headers().firstValue("Retry-After")).as("nobody can promise a time")
                    .isEmpty();
            assertThat(full.body() + full.headers().map()).doesNotContain("203.0.113");
            assertBaseSecurityHeaders(full);
            assertThat(process.calls().limitedInvocations())
                    .as("the rejected request reached no controller").isEqualTo(1);
            assertThat(asClient(process, "203.0.113.40").statusCode())
                    .as("the live entry is neither evicted nor refused").isEqualTo(200);
        }
    }

    @Test
    void aFullTableLeavesOneFixedWarnWithThePolicyAndNothingOfTheClient() {
        try (HarnessProcess process = start(PREFIX + "max-entries=1")) {
            captureTheCapacitySignal();
            asClient(process, "203.0.113.50");
            assertThat(metricEvents.list).as("an admission raises no alert").isEmpty();

            asClient(process, "203.0.113.51");

            assertThat(metricEvents.list).hasSize(1);
            ILoggingEvent event = metricEvents.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("rate limit capacity exhausted");
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                    .containsExactly("event=rate_limit_capacity_exhausted", "policy=admin-login",
                            "reason=table_full", "suppressed=0");
            assertThat(everythingOf(event)).doesNotContain("203.0.113")
                    .doesNotContain("/test/limited").doesNotContainIgnoringCase(FORWARDED);
        }
    }

    /**
     * Review S-3: that Spring maps a {@code HEAD} request to the handler of {@code GET} is a fact of
     * the real handler mapping, so it is proven here, through the real chain, and not assumed by a
     * unit test of the interceptor. A {@code HEAD} is limited exactly like the {@code GET} it is
     * mapped to: it counts against the same client, and a rejected one reaches no controller.
     */
    @Test
    void aHeadRequestToTheLimitedGetHandlerIsLimitedExactlyLikeAGet() {
        try (HarnessProcess process = start(PREFIX + "request-limit=2")) {
            Calls calls = process.calls();

            HttpResponse<String> first = process.send("HEAD", "/test/limited", FORWARDED,
                    "203.0.113.70");
            assertThat(first.statusCode()).as("non-vacuous: a HEAD within the limit is served")
                    .isEqualTo(200);
            assertThat(calls.limitedInvocations()).as("and it reached the handler").isEqualTo(1);
            assertThat(asClient(process, "203.0.113.70").statusCode())
                    .as("a GET after it counts against the same client").isEqualTo(200);

            HttpResponse<String> rejected = process.send("HEAD", "/test/limited", FORWARDED,
                    "203.0.113.70");

            assertThat(rejected.statusCode()).isEqualTo(429);
            String wait = rejected.headers().firstValue("Retry-After").orElseThrow();
            assertThat(wait).matches("[0-9]+");
            assertThat(Long.parseLong(wait)).isBetween(1L, 60L);
            assertThat(calls.limitedInvocations()).as("the rejected HEAD reached no controller")
                    .isEqualTo(2);
            assertThat(calls.useCaseInvocations()).isEqualTo(2);
        }
    }

    @Test
    void aRequestOverItsLimitIsNotACapacityProblemAndRaisesNoAlert() {
        try (HarnessProcess process = start(PREFIX + "request-limit=1")) {
            captureTheCapacitySignal();
            asClient(process, "203.0.113.60");
            assertThat(asClient(process, "203.0.113.60").statusCode()).isEqualTo(429);

            assertThat(metricEvents.list).isEmpty();
        }
    }

    @Test
    void withNothingSetTheEffectiveValuesAreTheOnesOfLayersOneAndTwo() {
        try (HarnessProcess process = start()) {
            RateLimitPolicy policy = process.bean(RateLimitProperties.class).policy();

            assertThat(policy).isEqualTo(new RateLimitPolicy(10, Duration.ofMinutes(1), 10,
                    Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofHours(1), 50_000));
        }
    }

    @Test
    void aRouteThatNamesAnUnknownPolicyStopsTheStartAndNamesThePolicy() {
        assertThatThrownBy(() -> start("harness.unknown-policy=true"))
                .satisfies(failure -> assertThat(messagesOf(failure)).contains("no-such-policy"));
    }

    /**
     * Review S-b: the check of the policies runs once every singleton exists, which is before the
     * context finishes refreshing, and the web server accepts a connection only when it is started
     * by that last step. A typo in the policy must never be a process that served requests with the
     * route unlimited, so the start that fails must not have started the server at all.
     */
    @Test
    void theStartThatNamesAnUnknownPolicyFailsBeforeTheWebServerAcceptsAConnection() {
        List<ApplicationEvent> started = new ArrayList<>();

        assertThatThrownBy(() -> HarnessProcess.startObserved(
                event -> {
                    if (event instanceof WebServerInitializedEvent) {
                        started.add(event);
                    }
                }, TRUST_LOOPBACK, "harness.unknown-policy=true"))
                .satisfies(failure -> assertThat(messagesOf(failure)).contains("no-such-policy")
                        .contains("admin-login"));

        assertThat(started).as("the web server was never started").isEmpty();

        List<ApplicationEvent> startedNormally = new ArrayList<>();
        try (HarnessProcess ignored = HarnessProcess.startObserved(
                event -> {
                    if (event instanceof WebServerInitializedEvent) {
                        startedNormally.add(event);
                    }
                }, TRUST_LOOPBACK)) {
            assertThat(startedNormally).as("non-vacuous: a start that succeeds does start the server")
                    .hasSize(1);
        }
    }

    @Test
    void aValueThatIsNotPositiveStopsTheProcessAndNamesTheProperty() {
        assertThatThrownBy(() -> start(PREFIX + "request-limit=0"))
                .satisfies(failure -> assertThat(messagesOf(failure))
                        .contains("confia.web.rate-limit.admin-login.request-limit"));
    }

    private static String everythingOf(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(' ')
                .append(event.getMDCPropertyMap());
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            text.append(' ').append(pair.key).append('=').append(pair.value);
        }
        return text.toString();
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append(" | ");
        }
        return messages.toString();
    }
}
