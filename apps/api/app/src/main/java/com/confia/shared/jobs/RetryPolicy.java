package com.confia.shared.jobs;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The bounded retry policy of a one-time job type (background-jobs-with-db-scheduler design.md
 * decision 9; ADR-0016 rule 6): at most {@value #MAX_ATTEMPTS_LIMIT} attempts, an exponential wait
 * between them and a cap on that wait. When the attempts are spent the execution is parked, never
 * dropped.
 *
 * @param maxAttempts total attempts, the first one included, from 1 to {@value #MAX_ATTEMPTS_LIMIT}
 * @param firstDelay the wait after the first failure
 * @param multiplier the factor applied to the wait after each further failure, at least 1
 * @param maxDelay the longest wait, never below {@code firstDelay}
 */
public record RetryPolicy(int maxAttempts, Duration firstDelay, double multiplier, Duration maxDelay) {

    /** The most attempts any job type may declare. */
    public static final int MAX_ATTEMPTS_LIMIT = 5;

    public RetryPolicy {
        Objects.requireNonNull(firstDelay, "firstDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException(
                    "maxAttempts must be from 1 to " + MAX_ATTEMPTS_LIMIT + " but was " + maxAttempts);
        }
        if (firstDelay.isZero() || firstDelay.isNegative()) {
            throw new IllegalArgumentException("firstDelay must be positive");
        }
        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be a finite number of at least 1");
        }
        if (maxDelay.compareTo(firstDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be below firstDelay");
        }
    }

    /**
     * The wait before the next attempt, after {@code consecutiveFailures} failures in a row; empty
     * once the attempts are spent, which is when the caller parks the execution.
     *
     * @param consecutiveFailures failures so far, counting the one that just happened; at least 1
     */
    public Optional<Duration> delayAfterFailure(int consecutiveFailures) {
        if (consecutiveFailures < 1) {
            throw new IllegalArgumentException("consecutiveFailures must be at least 1");
        }
        if (consecutiveFailures >= maxAttempts) {
            return Optional.empty();
        }
        double millis = firstDelay.toMillis() * Math.pow(multiplier, consecutiveFailures - 1.0);
        double capped = Math.min(millis, maxDelay.toMillis());
        return Optional.of(Duration.ofMillis((long) capped));
    }
}
