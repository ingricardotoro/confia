/**
 * Wiring of the background-jobs infrastructure (background-jobs-with-db-scheduler design.md
 * decisions 1 and 12): the data access of the worker process and, as the change advances, its
 * scheduler and the job client of the administrative and portal processes.
 *
 * <p><b>{@code @NamedInterface} (ADR-0022), and who consumes it.</b> The only consumer is {@code
 * bootstrap}: the entry point of each process imports the configuration it needs from here with
 * {@code @Import}. No business module imports this package.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.jobs.infrastructure.wiring;
