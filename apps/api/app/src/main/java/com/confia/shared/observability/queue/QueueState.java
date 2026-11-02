package com.confia.shared.observability.queue;

/**
 * The state the readiness of a process reports about the queue (background-jobs-with-db-scheduler design.md
 * decision 20). A degraded state informs; it never takes the process out of traffic (docs/07 section 9).
 */
public enum QueueState {

    /** The oldest due task has waited 900 seconds or less, or nothing is due. */
    HEALTHY,

    /** The oldest due task has waited strictly more than 900 seconds. */
    DEGRADED
}
