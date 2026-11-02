package com.confia.shared.observability.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The readiness rule of the queue (background-jobs-with-db-scheduler design.md decision 20): degraded when the
 * oldest due task has waited strictly more than 900 seconds, normal at 900 and below and when the queue is empty.
 * It is the method the readiness indicator of the observability changes calls, and a degraded answer informs
 * without taking the process out of traffic (docs/07 section 9).
 */
public final class QueueHealth {

    /** How long the oldest due task may wait before the queue is degraded. */
    public static final Duration STALLED_AFTER = Duration.ofSeconds(900);

    private final QueueStateReader reader;

    public QueueHealth(QueueStateReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** The state of the queue at {@code now}. */
    public QueueState evaluate(Instant now) {
        Objects.requireNonNull(now, "now");
        return stateOf(reader.read(now));
    }

    /** The state a reading implies: the same rule, for who already holds the reading. */
    public static QueueState stateOf(QueueSnapshot snapshot) {
        return snapshot.oldestDueAge().compareTo(STALLED_AFTER) > 0 ? QueueState.DEGRADED : QueueState.HEALTHY;
    }
}
