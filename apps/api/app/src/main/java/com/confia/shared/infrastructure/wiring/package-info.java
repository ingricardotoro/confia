/**
 * Wiring of the {@code shared} jobs for the worker process (background-jobs-with-db-scheduler design.md
 * decision 12): the configuration that declares the idempotency store, the purge of idempotency keys and its
 * recurring job, and, as the change advances, the audit-chain verification and the observation of the queue.
 *
 * <p><b>{@code @NamedInterface} (ADR-0022), and who consumes it.</b> The only consumer is {@code
 * bootstrap}: the entry point of the worker imports the configuration from here with {@code @Import}. No
 * business module imports this package.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.infrastructure.wiring;
