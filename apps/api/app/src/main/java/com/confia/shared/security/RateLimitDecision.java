package com.confia.shared.security;

import java.time.Duration;
import java.util.Objects;

/** The answer of a {@link RateLimiter} to one request (web-edge-foundations design.md, decision 15). */
public sealed interface RateLimitDecision {

    /** The request may proceed and has been counted. */
    record Admitted() implements RateLimitDecision {}

    /**
     * The request is rejected and the client must wait.
     *
     * @param retryAfter how long until the earliest instant the same request would be admitted
     *     (the longer wait when both layers reject); it is never zero or negative
     */
    record Limited(Duration retryAfter) implements RateLimitDecision {

        private static final long NANOS_PER_SECOND = 1_000_000_000L;

        public Limited {
            Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isZero() || retryAfter.isNegative()) {
                throw new IllegalArgumentException("a limited request waits for a positive time");
            }
        }

        /**
         * The value of {@code Retry-After}: whole seconds, rounded up, never below one. Rounding up
         * means the client is never told to come back before it would be admitted.
         */
        public long retryAfterSeconds() {
            long seconds = retryAfter.getSeconds();
            return retryAfter.getNano() == 0 ? Math.max(1, seconds) : seconds + 1;
        }
    }

    /**
     * The limiter cannot take another client: its table is full of live entries, none of which it
     * will evict. It does not say which client caused it.
     */
    record CapacityExhausted() implements RateLimitDecision {}
}
