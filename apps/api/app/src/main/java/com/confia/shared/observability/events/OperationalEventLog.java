package com.confia.shared.observability.events;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the one line an operational event leaves in the log: a {@code WARN} that names the event and
 * carries the identifier of the job run, the name of the task and the identifier of the institution, so
 * the line can be matched with the audit trail, and nothing else. Neither the task name (a closed
 * identifier) nor the institution (a {@link UUID}, never personal data) can carry free text; what the
 * event is about beyond that (the counts) is in the audit entries of the run, never in a log (CLAUDE.md,
 * regla 11).
 *
 * <p>It is the door through which a module asks for a line about its own operation (a job that leaves
 * work for the next occurrence, for example). {@link SecurityEventLog} stays the door for security
 * events; the two are separate so a line is never read as the wrong kind of signal.
 *
 * <p>It lives in {@code shared} and not in a module because the scope-exclusion inventory of {@code
 * com.confia.identity} forbids a logging framework there: the module's own record is its audit trail.
 *
 * <p>The event name is a closed identifier ({@code [a-z][a-z0-9_.]{0,79}}), so a caller cannot make
 * the line carry free text, a line break or a forged field.
 */
public final class OperationalEventLog {

    private static final Logger LOG = LoggerFactory.getLogger(OperationalEventLog.class);
    private static final Pattern EVENT_NAME = Pattern.compile("[a-z][a-z0-9_.]{0,79}");
    private static final Pattern TASK_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,79}");

    /**
     * Writes one {@code WARN} whose message is {@code event} and whose only fields are the run
     * identifier, the task name and the institution identifier.
     *
     * @throws IllegalArgumentException if {@code event} or {@code taskName} is not a closed identifier
     *     ({@code taskName} also admits {@code -}); nothing is written
     */
    public void warn(String event, UUID runId, String taskName, UUID institutionId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(institutionId, "institutionId");
        if (!EVENT_NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("an operational event name is a lowercase identifier of "
                    + "at most 80 characters");
        }
        if (!TASK_NAME.matcher(taskName).matches()) {
            throw new IllegalArgumentException("a task name is a lowercase identifier of at most 80 "
                    + "characters");
        }
        LOG.atWarn().addKeyValue("runId", runId.toString()).addKeyValue("task", taskName)
                .addKeyValue("institution", institutionId.toString()).log(event);
    }
}
