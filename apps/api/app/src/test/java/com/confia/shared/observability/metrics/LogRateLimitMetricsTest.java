package com.confia.shared.observability.metrics;

import static com.confia.shared.web.ratelimit.CapacityReason.LIMITER_FAILURE;
import static com.confia.shared.web.ratelimit.CapacityReason.NO_ADDRESS;
import static com.confia.shared.web.ratelimit.CapacityReason.NO_ORIGIN;
import static com.confia.shared.web.ratelimit.CapacityReason.TABLE_FULL;
import static com.confia.shared.web.ratelimit.CapacityReason.UNKNOWN_POLICY;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.ratelimit.CapacityReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/**
 * The interim adapter of the rate limiter's capacity signal (web-edge-foundations design.md,
 * decision 17, dated notes of 2026-10-05 and 2026-10-06; review findings I-2): one fixed,
 * structured {@code WARN} event each time the limiter cannot take a request, which the alert is
 * mounted on. The event carries the policy, the closed reason that tells an attack (a full table)
 * from a wiring defect, and the number of events held back, and nothing of the client or the
 * request. It is bounded: at most one event per second per policy and reason, with the held-back
 * count on the next one. Time is a source the test moves.
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
    void theEventIsOneFixedWarnWithExactlyTheEventNameThePolicyTheReasonAndTheHeldBackCount() {
        metrics.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getLoggerName()).isEqualTo(LogRateLimitMetrics.class.getName());
        assertThat(event.getFormattedMessage()).isEqualTo("rate limit capacity exhausted");
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(fieldsOf(event)).containsExactly(Map.entry("event", EVENT),
                Map.entry("policy", "admin-login"), Map.entry("reason", "table_full"),
                Map.entry("suppressed", 0L));
    }

    @ParameterizedTest
    @CsvSource({"TABLE_FULL, table_full", "NO_ORIGIN, no_origin", "NO_ADDRESS, no_address",
            "UNKNOWN_POLICY, unknown_policy", "LIMITER_FAILURE, limiter_failure"})
    void eachReasonIsWrittenAsItsFixedSnakeCaseValue(CapacityReason reason, String value) {
        metrics.capacityExhausted("admin-login", reason);

        assertThat(fieldsOf(logged.list.get(0))).containsEntry("reason", value);
    }

    @Test
    void theReasonsAreExactlyTheFiveCausesTheEdgeKnows() {
        assertThat(CapacityReason.values()).containsExactly(TABLE_FULL, NO_ORIGIN, NO_ADDRESS,
                UNKNOWN_POLICY, LIMITER_FAILURE);
    }

    @Test
    void theEventCarriesNothingOfAClientOrARequestBecauseThePortKnowsOnlyThePolicyAndTheReason() {
        metrics.capacityExhausted("admin-login", LIMITER_FAILURE);

        ILoggingEvent event = logged.list.get(0);
        String everything = event.getFormattedMessage() + event.getMDCPropertyMap()
                + fieldsOf(event) + java.util.Arrays.toString(event.getArgumentArray());
        assertThat(everything).doesNotContain("203.0.113").doesNotContain("/test/")
                .doesNotContainIgnoringCase("x-forwarded-for").doesNotContain("Bearer");
        assertThat(fieldsOf(event).keySet()).containsExactly("event", "policy", "reason",
                "suppressed");
        assertThat(event.getThrowableProxy()).as("never an exception, whose message may carry data")
                .isNull();
    }

    @Test
    void aFloodWithinOneSecondLeavesOneEventAndTheNextCarriesTheCountHeldBack() {
        for (int i = 0; i < 1_000; i++) {
            metrics.capacityExhausted("admin-login", TABLE_FULL);
        }

        assertThat(logged.list).as("1,000 rejections in one instant are one event").hasSize(1);

        advance(SECOND.minusNanos(1));
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        assertThat(logged.list).as("a nanosecond short of the second is still held back")
                .hasSize(1);

        advance(Duration.ofNanos(1));
        metrics.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(logged.list).hasSize(2);
        assertThat(fieldsOf(logged.list.get(1))).containsEntry("suppressed", 1_000L);
    }

    @Test
    void aQuietSecondAfterAnEventStartsFromZeroHeldBack() {
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        advance(SECOND);
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        advance(SECOND.multipliedBy(5));
        metrics.capacityExhausted("admin-login", TABLE_FULL);

        List<Object> held = new ArrayList<>();
        logged.list.forEach(event -> held.add(fieldsOf(event).get("suppressed")));
        assertThat(held).containsExactly(0L, 0L, 0L);
    }

    @Test
    void eachPolicyHasItsOwnBound() {
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        metrics.capacityExhausted("portal-login", TABLE_FULL);
        metrics.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(logged.list).extracting(event -> fieldsOf(event).get("policy"))
                .containsExactly("admin-login", "portal-login");
        assertThat(fieldsOf(logged.list.get(1))).containsEntry("suppressed", 0L);

        advance(SECOND);
        metrics.capacityExhausted("portal-login", TABLE_FULL);

        assertThat(logged.list).hasSize(3);
        assertThat(fieldsOf(logged.list.get(2))).containsEntry("policy", "portal-login")
                .containsEntry("suppressed", 0L);
    }

    /**
     * The bound is per policy <em>and reason</em>, not per policy alone. A flood of a full table,
     * which is an attack and is by far the most frequent reason, must not hide in the same second
     * a single wiring defect (a missing origin, an unknown policy, a failing limiter), which is
     * rare, is the cause an operator must act on and would otherwise be counted as {@code
     * suppressed} of the other. The state is still closed: a policy times five reasons.
     */
    @Test
    void eachReasonOfAPolicyHasItsOwnBoundSoAFloodOfOneNeverHidesAnother() {
        for (int i = 0; i < 500; i++) {
            metrics.capacityExhausted("admin-login", TABLE_FULL);
        }
        metrics.capacityExhausted("admin-login", LIMITER_FAILURE);
        metrics.capacityExhausted("admin-login", NO_ORIGIN);
        metrics.capacityExhausted("admin-login", NO_ADDRESS);
        metrics.capacityExhausted("admin-login", UNKNOWN_POLICY);
        metrics.capacityExhausted("admin-login", LIMITER_FAILURE);

        assertThat(logged.list).extracting(event -> fieldsOf(event).get("reason"))
                .containsExactly("table_full", "limiter_failure", "no_origin", "no_address",
                        "unknown_policy");

        advance(SECOND);
        metrics.capacityExhausted("admin-login", LIMITER_FAILURE);
        metrics.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(logged.list).hasSize(7);
        assertThat(fieldsOf(logged.list.get(5))).containsEntry("reason", "limiter_failure")
                .containsEntry("suppressed", 1L);
        assertThat(fieldsOf(logged.list.get(6))).containsEntry("reason", "table_full")
                .containsEntry("suppressed", 499L);
    }

    @Test
    void whatIsHeldBackAfterTheLastEmittedEventIsReportedOnlyWithTheNextOne() {
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        metrics.capacityExhausted("admin-login", TABLE_FULL);
        advance(SECOND.multipliedBy(30));

        assertThat(logged.list).as("nothing is written on its own: the two held back wait")
                .hasSize(1);

        metrics.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(fieldsOf(logged.list.get(1))).containsEntry("suppressed", 2L);
    }

    @Test
    void theProductionConstructorCountsWithTheMonotonicSourceAndEmitsTheFirstEvent() {
        // A wall clock stepping back could silence the alert and one stepping forward could flood
        // it, so the one constructor of production takes System.nanoTime and nothing else.
        LogRateLimitMetrics production = new LogRateLimitMetrics();

        production.capacityExhausted("admin-login", TABLE_FULL);
        production.capacityExhausted("admin-login", TABLE_FULL);

        assertThat(logged.list).as("the second falls within the same second").hasSize(1);
    }
}
