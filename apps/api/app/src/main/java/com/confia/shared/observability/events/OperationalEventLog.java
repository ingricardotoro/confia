package com.confia.shared.observability.events;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

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
 *
 * <p>{@link #info} and {@link #error} add closed details after those three fields, for the events whose
 * meaning needs them (the verification of the audit chain: the scope, the rows verified, the identifier of
 * the first divergent row). A detail is a number or a word of at most 40 letters, digits and underscores,
 * under a name of the same closed shape, so it too can never carry free text.
 */
public final class OperationalEventLog {

    private static final Logger LOG = LoggerFactory.getLogger(OperationalEventLog.class);
    private static final Pattern EVENT_NAME = Pattern.compile("[a-z][a-z0-9_.]{0,79}");
    private static final Pattern TASK_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,79}");
    private static final Pattern DETAIL_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
    private static final Pattern DETAIL_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Set<String> COMMON_FIELDS = Set.of("runId", "task", "institution");

    /**
     * Writes one {@code WARN} whose message is {@code event} and whose only fields are the run
     * identifier, the task name and the institution identifier.
     *
     * @throws IllegalArgumentException if {@code event} or {@code taskName} is not a closed identifier
     *     ({@code taskName} also admits {@code -}); nothing is written
     */
    public void warn(String event, UUID runId, String taskName, UUID institutionId) {
        write(Level.WARN, event, runId, taskName, institutionId, Map.of());
    }

    /**
     * Writes one {@code WARN} like {@link #warn(String, UUID, String, UUID)}, followed by {@code details}.
     *
     * @param details name and value in the order they are written; each value is a {@code Long}, an {@code
     *     Integer} or a word of at most 40 letters, digits and underscores
     * @throws IllegalArgumentException if the event, the task or a detail is not closed; nothing is written
     */
    public void warn(String event, UUID runId, String taskName, UUID institutionId, Map<String, ?> details) {
        write(Level.WARN, event, runId, taskName, institutionId, details);
    }

    /** Writes one {@code INFO} like {@link #warn(String, UUID, String, UUID, Map)}. */
    public void info(String event, UUID runId, String taskName, UUID institutionId, Map<String, ?> details) {
        write(Level.INFO, event, runId, taskName, institutionId, details);
    }

    /** Writes one {@code ERROR} like {@link #warn(String, UUID, String, UUID, Map)}. */
    public void error(String event, UUID runId, String taskName, UUID institutionId, Map<String, ?> details) {
        write(Level.ERROR, event, runId, taskName, institutionId, details);
    }

    private static void write(Level level, String event, UUID runId, String taskName, UUID institutionId,
            Map<String, ?> details) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(taskName, "taskName");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(details, "details");
        if (!EVENT_NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("an operational event name is a lowercase identifier of "
                    + "at most 80 characters");
        }
        if (!TASK_NAME.matcher(taskName).matches()) {
            throw new IllegalArgumentException("a task name is a lowercase identifier of at most 80 "
                    + "characters");
        }
        details.forEach(OperationalEventLog::requireClosedDetail);
        var line = LOG.atLevel(level).addKeyValue("runId", runId.toString()).addKeyValue("task", taskName)
                .addKeyValue("institution", institutionId.toString());
        for (Map.Entry<String, ?> detail : details.entrySet()) {
            line = line.addKeyValue(detail.getKey(), detail.getValue());
        }
        line.log(event);
    }

    private static void requireClosedDetail(String name, Object value) {
        if (!DETAIL_NAME.matcher(name).matches() || COMMON_FIELDS.contains(name)) {
            throw new IllegalArgumentException("an operational event detail is named by a lowercase word of "
                    + "at most 40 letters and digits that is not one of the common fields");
        }
        boolean closed = value instanceof Long || value instanceof Integer
                || (value instanceof String word && DETAIL_WORD.matcher(word).matches());
        if (!closed) {
            throw new IllegalArgumentException("an operational event detail is a number or a word of at most "
                    + "40 letters, digits and underscores");
        }
    }
}
