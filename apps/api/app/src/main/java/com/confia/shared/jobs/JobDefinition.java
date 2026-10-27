package com.confia.shared.jobs;

/**
 * What a module declares to the worker (background-jobs-with-db-scheduler design.md decision 7): a
 * one-time job with its handler, or a recurring job with its calendar and handler. The worker
 * receives every definition as a {@code List<JobDefinition>}, fails to start if two share a name, and
 * translates them into the library's tasks in one class, so no module imports the library.
 */
public sealed interface JobDefinition permits OneTimeJob, RecurringJob {

    /** The unique task name. */
    String name();
}
