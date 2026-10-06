package com.confia.shared.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * The interim adapter of the rate limiter's capacity signal (web-edge-foundations design.md,
 * decision 17, dated note of 2026-10-05; review finding I-2): one fixed, structured {@code WARN}
 * event each time the limiter answers {@code CapacityExhausted}, which the alert is mounted on. The
 * event carries the policy and the number of events held back, and nothing of the client or the
 * request. It is bounded: at most one event per second per policy, with the held-back count on the
 * next one, so that a flood of rejections cannot flood the log. Time is a source the test moves.
 */
class LogRateLimitMetricsTest {

    private static final String EVENT = "rate_limit_capacity_exhausted";
    private static final Duration SECOND = Duration.ofSeconds(1);

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(LogRateLimitMetrics.class);
    private final AtomicLong nanos = new AtomicLong(-987_654_321_000L);
    private final LogRateLimitMetrics metrics = new LogRateLimitMetrics(nanos::get);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    private void advance(Duration amount) {
        nanos.addAndGet(amount.toNanos());
    }

    private static Map<String, Object> fieldsOf(ILoggingEvent event) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        for (KeyValuePair pair : event.getKeyValuePairs()) {
            fields.put(pair.key, pair.value);
        }
        return fields;
    }

    @Test
    void theEventIsOneFixedWarnWithExactlyTheEventNameThePolicyAndTheHeldBackCount() {
        metrics.capacityExhausted("admin-login");

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getLoggerName()).isEqualTo(LogRateLimitMetrics.class.getName());
        assertThat(event.getFormattedMessage()).isEqualTo("rate limit capacity exhausted");
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(fieldsOf(event)).containsExactly(Map.entry("event", EVENT),
                Map.entry("policy", "admin-login"), Map.entry("suppressed", 0L));
    }

    @Test
    void theEventCarriesNothingOfAClientOrARequestBecauseThePortKnowsOnlyThePolicy() {
        metrics.capacityExhausted("admin-login");

        ILoggingEvent event = logged.list.get(0);
        String everything = event.getFormattedMessage() + event.getMDCPropertyMap()
                + fieldsOf(event) + java.util.Arrays.toString(event.getArgumentArray());
        assertThat(everything).doesNotContain("203.0.113").doesNotContain("/test/")
                .doesNotContainIgnoringCase("x-forwarded-for").doesNotContain("Bearer");
        assertThat(fieldsOf(event).keySet()).containsExactly("event", "policy", "suppressed");
    }

    @Test
    void aFloodWithinOneSecondLeavesOneEventAndTheNextCarriesTheCountHeldBack() {
        for (int i = 0; i < 1_000; i++) {
            metrics.capacityExhausted("admin-login");
        }

        assertThat(logged.list).as("1,000 rejections in one instant are one event").hasSize(1);

        advance(SECOND.minusNanos(1));
        metrics.capacityExhausted("admin-login");
        assertThat(logged.list).as("a nanosecond short of the second is still held back")
                .hasSize(1);

        advance(Duration.ofNanos(1));
        metrics.capacityExhausted("admin-login");

        assertThat(logged.list).hasSize(2);
        assertThat(fieldsOf(logged.list.get(1))).containsEntry("suppressed", 1_000L);
    }

    @Test
    void aQuietSecondAfterAnEventStartsFromZeroHeldBack() {
        metrics.capacityExhausted("admin-login");
        advance(SECOND);
        metrics.capacityExhausted("admin-login");
        advance(SECOND.multipliedBy(5));
        metrics.capacityExhausted("admin-login");

        List<Object> held = new ArrayList<>();
        logged.list.forEach(event -> held.add(fieldsOf(event).get("suppressed")));
        assertThat(held).containsExactly(0L, 0L, 0L);
    }

    @Test
    void eachPolicyHasItsOwnBound() {
        metrics.capacityExhausted("admin-login");
        metrics.capacityExhausted("portal-login");
        metrics.capacityExhausted("admin-login");

        assertThat(logged.list).extracting(event -> fieldsOf(event).get("policy"))
                .containsExactly("admin-login", "portal-login");
        assertThat(fieldsOf(logged.list.get(1))).containsEntry("suppressed", 0L);

        advance(SECOND);
        metrics.capacityExhausted("portal-login");

        assertThat(logged.list).hasSize(3);
        assertThat(fieldsOf(logged.list.get(2))).containsEntry("policy", "portal-login")
                .containsEntry("suppressed", 0L);
    }

    @Test
    void theProductionConstructorCountsWithTheMonotonicSourceAndEmitsTheFirstEvent() {
        // A wall clock stepping back could silence the alert and one stepping forward could flood
        // it, so the one constructor of production takes System.nanoTime and nothing else.
        LogRateLimitMetrics production = new LogRateLimitMetrics();

        production.capacityExhausted("admin-login");
        production.capacityExhausted("admin-login");

        assertThat(logged.list).as("the second falls within the same second").hasSize(1);
    }
}
