package com.confia.shared.jobs;

/**
 * The code a module supplies to run one execution of a {@link JobType}
 * (background-jobs-with-db-scheduler design.md decision 7). It runs in the worker, as the {@code
 * system} actor and inside the institution of the task, and it must be idempotent: the library
 * delivers at least once, so a second run with the same data must leave the same business state
 * (ADR-0016 compliance 5; scenario BJ19).
 *
 * <p>The handler opens its own transaction with {@code TransactionRunner}, using {@link
 * JobContext#systemContext()}; the executor never holds one for it.
 *
 * @param <T> the payload type
 */
@FunctionalInterface
public interface JobHandler<T extends Record> {

    void handle(T payload, JobContext context);
}
