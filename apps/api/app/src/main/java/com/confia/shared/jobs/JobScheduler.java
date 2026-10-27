package com.confia.shared.jobs;

import java.time.Instant;

/**
 * The port through which a use case schedules a one-time job, always inside the business
 * transaction that {@code TransactionRunner} opened (background-jobs-with-db-scheduler design.md
 * decision 6; ADR-0016 rule 3): the task exists if and only if that transaction commits.
 *
 * <p>An implementation refuses to schedule outside a transaction, refuses a {@link JobMeta} whose
 * institution is not the one of the transaction, and answers {@link ScheduleOutcome#ALREADY_SCHEDULED}
 * when the instance exists instead of failing, because a failed insert would abort the business
 * transaction.
 */
public interface JobScheduler {

    /**
     * Schedules one execution of {@code type}.
     *
     * @param type the job type
     * @param instance the deterministic identifier of this execution
     * @param payload the data of the job
     * @param meta the identifiers and context of the job
     * @param runAt the instant from which the worker may take it
     * @return {@link ScheduleOutcome#SCHEDULED}, or {@link ScheduleOutcome#ALREADY_SCHEDULED}
     * @throws IllegalStateException when no transaction is active
     */
    <T extends Record> ScheduleOutcome schedule(JobType<T> type, JobInstanceId instance, T payload,
            JobMeta meta, Instant runAt);
}
