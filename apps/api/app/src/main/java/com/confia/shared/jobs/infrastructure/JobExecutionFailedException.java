package com.confia.shared.jobs.infrastructure;

import java.sql.SQLException;
import java.util.Objects;

/**
 * What the worker reports when a handler fails (background-jobs-with-db-scheduler design.md decision
 * 10). The library logs a failed execution at {@code ERROR} with the whole stack trace, and the log
 * guard only filters {@code DEBUG} and {@code TRACE}; a PostgreSQL unique violation, for one, quotes
 * {@code Key (...)=(...)}. So the original exception never leaves the runner: this one carries the
 * task name, the class of the original exception and, when its cause chain holds an {@link
 * SQLException}, the {@code SQLState}. It has no cause, no original message and no suppressed
 * exceptions.
 */
public final class JobExecutionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String taskName;
    private final String originalClass;
    private final String sqlState;

    private JobExecutionFailedException(String taskName, String originalClass, String sqlState) {
        super("job '" + taskName + "' failed: " + originalClass
                + (sqlState == null ? "" : " (SQLState " + sqlState + ")"), null, false, true);
        this.taskName = taskName;
        this.originalClass = originalClass;
        this.sqlState = sqlState;
    }

    /** Sanitizes {@code original}: only its class and the first {@code SQLState} of its chain remain. */
    static JobExecutionFailedException of(String taskName, Throwable original) {
        Objects.requireNonNull(original, "original");
        String sqlState = null;
        Throwable cause = original;
        for (int depth = 0; cause != null && sqlState == null && depth < 32; depth++) {
            if (cause instanceof SQLException sql) {
                sqlState = sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return new JobExecutionFailedException(taskName, original.getClass().getName(), sqlState);
    }

    public String taskName() {
        return taskName;
    }

    public String originalClass() {
        return originalClass;
    }

    /** The {@code SQLState} found in the cause chain of the original exception, or {@code null}. */
    public String sqlState() {
        return sqlState;
    }
}
