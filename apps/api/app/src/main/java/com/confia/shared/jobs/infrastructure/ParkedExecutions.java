package com.confia.shared.jobs.infrastructure;

import java.time.Instant;

/**
 * Where an exhausted execution is parked (background-jobs-with-db-scheduler design.md decision 9).
 * The library's own policy deletes an execution when its retries run out and discards it silently;
 * this system keeps it: the row is rescheduled to {@link #PARKED_AT}, so no poll ever takes it, it
 * keeps {@code consecutive_failures} and {@code last_failure}, and the queue metrics count it as
 * failed. No calendar reaches the year 9999, which the calendar test asserts.
 */
public final class ParkedExecutions {

    /** The instant of every parked execution: {@code 9999-01-01T00:00:00Z}. */
    public static final Instant PARKED_AT = Instant.parse("9999-01-01T00:00:00Z");

    private ParkedExecutions() {
    }

    /** Whether {@code executionTime} is the parking instant. */
    public static boolean isParked(Instant executionTime) {
        return !executionTime.isBefore(PARKED_AT);
    }
}
