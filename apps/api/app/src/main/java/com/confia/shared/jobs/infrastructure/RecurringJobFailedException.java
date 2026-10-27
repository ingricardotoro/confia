package com.confia.shared.jobs.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Thrown by {@link RecurringJobRunner} after it has run every institution, when at least one failed,
 * so that the library records the occurrence as failed ({@code consecutive_failures} above zero) while
 * the other institutions have already been served (background-jobs-with-db-scheduler design.md
 * decision 10; scenario BJ65). It names the task and the institutions that failed, which are
 * configuration identifiers and not personal data; it carries no cause and no message of the original
 * exceptions.
 */
public final class RecurringJobFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String taskName;
    private final List<UUID> failedInstitutions;

    RecurringJobFailedException(String taskName, List<UUID> failedInstitutions) {
        super("recurring job '" + Objects.requireNonNull(taskName, "taskName") + "' failed for "
                + failedInstitutions.size() + " institution(s): " + failedInstitutions, null, false, true);
        this.taskName = taskName;
        this.failedInstitutions = List.copyOf(failedInstitutions);
    }

    public String taskName() {
        return taskName;
    }

    public List<UUID> failedInstitutions() {
        return failedInstitutions;
    }
}
