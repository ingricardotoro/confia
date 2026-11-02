package com.confia.shared.observability.queue;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the queue holds at one instant, counted by state and nothing else (background-jobs-with-db-scheduler
 * design.md decision 20). It has no task data, no instance identifier and no institution, so neither a log line
 * nor a readiness answer built from it can carry them.
 *
 * @param scheduled tasks set for a later instant
 * @param due tasks whose instant arrived and that no worker has taken
 * @param running tasks taken by a worker, beating or not
 * @param failedByTask failed tasks, parked or recurring with a failure on their last occurrence, counted for each
 *     task name; copied and sorted by name
 * @param oldestDueAge how long the oldest due task has waited, or the oldest taken task whose worker stopped
 *     beating; zero when none; a parked task never counts
 */
public record QueueSnapshot(long scheduled, long due, long running, Map<String, Long> failedByTask,
        Duration oldestDueAge) {

    public QueueSnapshot {
        if (scheduled < 0 || due < 0 || running < 0) {
            throw new IllegalArgumentException("a count of tasks is not negative");
        }
        Objects.requireNonNull(failedByTask, "failedByTask");
        Objects.requireNonNull(oldestDueAge, "oldestDueAge");
        if (oldestDueAge.isNegative()) {
            throw new IllegalArgumentException("an age is not negative");
        }
        Map<String, Long> sorted = new TreeMap<>(failedByTask);
        if (sorted.values().stream().anyMatch(count -> count < 0)) {
            throw new IllegalArgumentException("a count of failed tasks is not negative");
        }
        failedByTask = Collections.unmodifiableMap(sorted);
    }

    /** A queue with nothing in it. */
    public static QueueSnapshot empty() {
        return new QueueSnapshot(0, 0, 0, Map.of(), Duration.ZERO);
    }

    /** The failed tasks of every name. */
    public long failed() {
        return failedByTask.values().stream().mapToLong(Long::longValue).sum();
    }

    /** {@link #oldestDueAge()} in whole seconds, the unit the events and the alerts use. */
    public long oldestDueAgeSeconds() {
        return oldestDueAge.getSeconds();
    }
}
