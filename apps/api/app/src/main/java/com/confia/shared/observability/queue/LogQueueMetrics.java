package com.confia.shared.observability.queue;

import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interim adapter of {@link QueueMetrics}: there is no metrics registry in the backend yet, so the queue is
 * reported as structured log events (background-jobs-with-db-scheduler design.md decision 20, P8), like {@link
 * com.confia.shared.observability.metrics.LogRateLimitMetrics} does for the capacity of the limiter. For each
 * observation it writes, in this order:
 *
 * <ul>
 *   <li>{@value #STATE_EVENT} at {@code INFO}, with {@code scheduled}, {@code due}, {@code running}, {@code failed}
 *       and {@code oldest_due_age_seconds};
 *   <li>{@value #TASK_FAILED_EVENT} at {@code WARN} for each task name that has failed tasks, with {@code task} and
 *       {@code failed}, in name order;
 *   <li>{@value #STALLED_EVENT} at {@code WARN}, with {@code oldest_due_age_seconds}, when the state is {@link
 *       QueueState#DEGRADED}.
 * </ul>
 *
 * <p>The message is the name of the event and the fields are key-value pairs, so an alert is mounted on the
 * message. A line holds counts and task names only. The snapshot has neither the data of a task nor its instance
 * identifier, and a task name that is not a closed identifier is written as {@value #UNRECOGNIZED_TASK}, so a row
 * written by hand cannot put free text or a forged field in a line.
 *
 * <p>The observation runs in the same queue it observes: if the whole executor stops, {@value #STATE_EVENT} stops
 * appearing, and the alert of the observability changes is on its <em>absence</em> for more than five minutes.
 */
public final class LogQueueMetrics implements QueueMetrics {

    static final String STATE_EVENT = "queue_state";
    static final String TASK_FAILED_EVENT = "queue_task_failed";
    static final String STALLED_EVENT = "queue_stalled";
    static final String UNRECOGNIZED_TASK = "unrecognized";

    private static final Logger LOG = LoggerFactory.getLogger(LogQueueMetrics.class);
    private static final Pattern TASK_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,79}");

    @Override
    public void observed(QueueSnapshot snapshot) {
        LOG.atInfo().addKeyValue("scheduled", snapshot.scheduled()).addKeyValue("due", snapshot.due())
                .addKeyValue("running", snapshot.running()).addKeyValue("failed", snapshot.failed())
                .addKeyValue("oldest_due_age_seconds", snapshot.oldestDueAgeSeconds()).log(STATE_EVENT);
        for (Map.Entry<String, Long> failure : snapshot.failedByTask().entrySet()) {
            LOG.atWarn().addKeyValue("task", closed(failure.getKey())).addKeyValue("failed", failure.getValue())
                    .log(TASK_FAILED_EVENT);
        }
        if (QueueHealth.stateOf(snapshot) == QueueState.DEGRADED) {
            LOG.atWarn().addKeyValue("oldest_due_age_seconds", snapshot.oldestDueAgeSeconds()).log(STALLED_EVENT);
        }
    }

    private static String closed(String taskName) {
        return TASK_NAME.matcher(taskName).matches() ? taskName : UNRECOGNIZED_TASK;
    }
}
