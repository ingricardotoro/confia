package com.confia.shared.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A monotonic nanosecond source a test moves by hand, so that no test of time-dependent logic ever
 * sleeps or reads a clock. It stands where {@code System::nanoTime} stands in production: its value
 * means nothing on its own, only the difference between two readings does, and so the default start
 * is an arbitrary negative number, which {@code System.nanoTime} is also allowed to return. It never
 * moves on its own and it never moves backwards, and it is safe to read from many threads while the
 * test thread advances it.
 */
final class MutableClock implements LongSupplier {

    private static final long ARBITRARY_START = -123_456_789_012_345L;

    private final AtomicLong nanos;

    MutableClock() {
        this(ARBITRARY_START);
    }

    MutableClock(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    /** Moves the source forward; a negative amount is a defect in the test, not a feature. */
    void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("a test clock never moves backwards");
        }
        nanos.addAndGet(amount.toNanos());
    }

    @Override
    public long getAsLong() {
        return nanos.get();
    }
}
