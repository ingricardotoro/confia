/**
 * The observation of the job queue (background-jobs-with-db-scheduler design.md decision 20, P8): the port that
 * reads it ({@link com.confia.shared.observability.queue.QueueStateReader}), what it reads ({@link
 * com.confia.shared.observability.queue.QueueSnapshot}), the readiness rule ({@link
 * com.confia.shared.observability.queue.QueueHealth}), the port that publishes it ({@link
 * com.confia.shared.observability.queue.QueueMetrics}) with its structured-log adapter ({@link
 * com.confia.shared.observability.queue.LogQueueMetrics}) and the recurring job that connects them ({@link
 * com.confia.shared.observability.queue.QueueObservation}). Actuator is present as the health module only and there is no Micrometer registry: the
 * adapter is replaced by the observability changes without touching the rest.
 *
 * <p>The package carries no layer segment, like {@link com.confia.shared.observability.metrics}. The only
 * adapter of the reader, {@code JooqScheduledTaskQueueReader}, lives in {@code shared.infrastructure} and is
 * the one reader of {@code scheduled_tasks} that may use plain SQL (ADR-0015 rule 9); it never reads the data of
 * a task.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of {@code shared}
 * names the real consumer that justifies it). Today the package is consumed from inside {@code shared}, by {@code
 * shared.infrastructure.wiring.SharedWorkerJobsConfiguration}, which registers the observation in the worker, so
 * the annotation is not yet needed by Spring Modulith. It is declared because the consumer it is for lives in
 * another module: the readiness indicator of the observability changes (10 and 11) calls {@link
 * com.confia.shared.observability.queue.QueueHealth#evaluate} to report the degraded state, and the alert on the
 * absence of {@code queue_state} is mounted on this package's events. The worker is the only process that loads
 * the observation (scenario BI113).
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.observability.queue;
