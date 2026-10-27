package com.confia.shared.jobs;

/**
 * The result of {@link JobScheduler#schedule}. Scheduling an instance that already exists is not an
 * error and never aborts the business transaction (scenario BJ44): it answers {@link
 * #ALREADY_SCHEDULED}.
 */
public enum ScheduleOutcome {
    SCHEDULED,
    ALREADY_SCHEDULED
}
