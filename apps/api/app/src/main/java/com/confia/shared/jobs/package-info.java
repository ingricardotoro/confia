/**
 * The port through which a module schedules and declares background jobs
 * (background-jobs-with-db-scheduler design.md decisions 3, 6, 7, 8 and 9; ADR-0016). Only types of
 * the JDK, {@code kernel} and {@code shared.security} appear here: a business module declares a
 * {@link com.confia.shared.jobs.JobType} and a {@link com.confia.shared.jobs.JobHandler}, or a
 * {@link com.confia.shared.jobs.RecurringJob}, and never imports the scheduling library, whose
 * types stay in {@code com.confia.shared.jobs.infrastructure}.
 *
 * <p>Nothing in this package is a bean. This package carries no layer segment ({@code domain},
 * {@code application}, {@code infrastructure} or {@code web}), like {@link com.confia.shared.security}.
 *
 * <p><b>{@code @NamedInterface} (ADR-0022), and who consumes it.</b> {@code identity} consumes it
 * (the password-reset issuance job, the retention purges) and so does {@code shared.audit} (the audit
 * chain verification cadences). Both arrive in the changes that follow this one in the chain;
 * {@code shared.jobs.infrastructure} is the first real consumer, and lives in the same module.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.jobs;
