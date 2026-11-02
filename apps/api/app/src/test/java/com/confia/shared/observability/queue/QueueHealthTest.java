package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenarios BJ34 and BJ35 of background-jobs-with-db-scheduler (design.md decision 20): the readiness state is
 * degraded when the age of the oldest due task is strictly greater than 900 seconds, normal at 900 and below, and
 * back to normal when the queue empties. The clock is a fixed instant handed to {@link QueueHealth#evaluate}, and
 * the reader is a stub, so no database and no wall clock take part.
 */
class QueueHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-01T18:00:00Z");

    private final AtomicReference<QueueSnapshot> queue = new AtomicReference<>(QueueSnapshot.empty());
    private final List<Instant> asked = new ArrayList<>();
    private final QueueHealth health = new QueueHealth(now -> {
        asked.add(now);
        return queue.get();
    });

    private static QueueSnapshot dueFor(Duration age) {
        return new QueueSnapshot(0, 1, 0, Map.of(), age);
    }

    @ParameterizedTest(name = "{0} s is healthy")
    @ValueSource(longs = {0, 1, 899, 900})
    void anAgeOfNineHundredSecondsOrLessIsHealthy(long seconds) {
        queue.set(dueFor(Duration.ofSeconds(seconds)));

        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.HEALTHY);
    }

    @ParameterizedTest(name = "{0} s is degraded")
    @ValueSource(longs = {901, 902, 86_400})
    void anAgeOfMoreThanNineHundredSecondsIsDegraded(long seconds) {
        queue.set(dueFor(Duration.ofSeconds(seconds)));

        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.DEGRADED);
    }

    @Test
    void theThresholdIsStrictEvenByOneNanosecond() {
        queue.set(dueFor(Duration.ofSeconds(900)));
        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.HEALTHY);

        queue.set(dueFor(Duration.ofSeconds(900).plusNanos(1)));
        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.DEGRADED);
    }

    @Test
    void theStateIsNormalAgainWhenTheQueueEmptiesAfterADegradedOne() {
        queue.set(dueFor(Duration.ofSeconds(950)));
        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.DEGRADED);

        queue.set(QueueSnapshot.empty());
        assertThat(health.evaluate(NOW.plusSeconds(60))).isEqualTo(QueueState.HEALTHY);
    }

    @Test
    void aParkedFailureAloneDoesNotDegradeTheQueue() {
        queue.set(new QueueSnapshot(2, 0, 0, Map.of("identity.password-reset-issuance", 3L), Duration.ZERO));

        assertThat(health.evaluate(NOW)).isEqualTo(QueueState.HEALTHY);
    }

    @Test
    void theReaderIsAskedAboutTheInstantItWasGiven() {
        health.evaluate(NOW);

        assertThat(asked).containsExactly(NOW);
    }

    @Test
    void theStateOfASnapshotIsTheSameRuleWithoutReading() {
        assertThat(QueueHealth.stateOf(dueFor(Duration.ofSeconds(900)))).isEqualTo(QueueState.HEALTHY);
        assertThat(QueueHealth.stateOf(dueFor(Duration.ofSeconds(901)))).isEqualTo(QueueState.DEGRADED);
        assertThat(asked).isEmpty();
    }

    @Test
    void theThresholdIsNineHundredSeconds() {
        assertThat(QueueHealth.STALLED_AFTER).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void aReaderIsRequired() {
        assertThatThrownBy(() -> new QueueHealth(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> health.evaluate(null)).isInstanceOf(NullPointerException.class);
    }
}
