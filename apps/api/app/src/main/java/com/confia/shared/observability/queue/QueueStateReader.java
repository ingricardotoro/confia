package com.confia.shared.observability.queue;

import java.time.Instant;

/**
 * The port that reads the queue (background-jobs-with-db-scheduler design.md decision 20). One read-only
 * statement answers it; it never reads the data of a task.
 */
@FunctionalInterface
public interface QueueStateReader {

    /** What the queue holds at {@code now}. */
    QueueSnapshot read(Instant now);
}
