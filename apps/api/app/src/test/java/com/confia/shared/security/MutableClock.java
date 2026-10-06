package com.confia.shared.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock a test moves by hand, so that no test of time-dependent logic ever sleeps or reads the
 * wall clock. It never moves on its own and it never moves backwards, and it is safe to read from
 * many threads while the test thread advances it.
 */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /** Moves the clock forward; a negative amount is a defect in the test, not a feature. */
    void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a test clock never moves backwards");
        }
        now.updateAndGet(current -> current.plus(amount));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
