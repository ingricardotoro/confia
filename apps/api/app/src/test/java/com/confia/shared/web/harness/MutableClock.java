package com.confia.shared.web.harness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} a test moves by hand, so no test of the cap and the window of the permission denials
 * reads the real time or waits (rbac-permission-matrix-and-audit-integration design.md D19, scenarios
 * BI137 and BI138). Unlike the nanosecond source of {@code shared.security}, this one is an instant
 * clock and it can move backwards, which the check of a backward step needs. The zone is UTC.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /** Moves the clock to {@code instant}, earlier or later than now. */
    public void set(Instant instant) {
        now.set(instant);
    }

    /** Moves the clock by {@code amount}; a negative amount moves it back. */
    public void advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
