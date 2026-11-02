package com.confia.shared.observability.queue;

/**
 * Where the observation of the queue sends what it read (background-jobs-with-db-scheduler design.md decision
 * 20, P8). Until the observability changes provide a metrics registry, the adapter is {@link LogQueueMetrics}; the
 * job that observes depends only on this port.
 */
@FunctionalInterface
public interface QueueMetrics {

    /** Publishes one observation of the queue. */
    void observed(QueueSnapshot snapshot);
}
