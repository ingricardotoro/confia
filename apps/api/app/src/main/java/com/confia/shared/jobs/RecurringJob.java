package com.confia.shared.jobs;

import java.util.Objects;

/**
 * A recurring job: it carries no data, runs on its calendar once for each configured institution,
 * and is never retried; a failed occurrence is rescheduled to the next one and stays visible until a
 * later success (background-jobs-with-db-scheduler design.md decisions 7, 9 and 10).
 *
 * @param name the unique task name
 * @param calendar when it runs
 * @param handler runs one occurrence for one institution
 */
public record RecurringJob(String name, JobCalendar calendar, RecurringJobHandler handler)
        implements JobDefinition {

    public RecurringJob {
        JobType.requireValidName(name);
        Objects.requireNonNull(calendar, "calendar");
        Objects.requireNonNull(handler, "handler");
    }
}
