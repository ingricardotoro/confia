package com.confia.shared.jobs.infrastructure;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.event.SchedulerListener.SchedulerEventType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The health of the worker as the modification time of a file (dockerfiles-and-local-compose design.md
 * decision 6; scenarios CR16, CR17, WE100). The scheduler tells its listeners {@code RAN_EXECUTE_DUE} each time
 * a polling cycle of due tasks ends, which happens every {@code db-scheduler.polling-interval} whether or not a
 * task was due and only when the query against {@code scheduled_tasks} worked. A fresh file therefore proves
 * at once that the process lives, that the scheduler polls and that PostgreSQL answers; with the database down
 * the cycle ends in {@code UNEXPECTED_ERROR}, the file stops moving and the orchestrator sees it age.
 *
 * <p>No thread of its own (the {@code NoInMemorySchedulingTest} bans them), no port, no route and no content:
 * the file is empty and only its date matters. A write that fails is logged once at {@code WARN}, with the
 * exception class and never its message, and never reaches the scheduler. The listener exists only when
 * {@code confia.health.heartbeat-file} is set, so the tests that start the worker write no file.
 */
public final class WorkerHeartbeatListener extends AbstractSchedulerListener {

    /** The property that names the file; Compose sets it through {@code CONFIA_HEALTH_HEARTBEATFILE}. */
    public static final String PROPERTY = "confia.health.heartbeat-file";

    private static final Logger LOG = LoggerFactory.getLogger(WorkerHeartbeatListener.class);

    private final Path file;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();

    WorkerHeartbeatListener(Path file) {
        this.file = file;
    }

    /** The listener for the value of {@link #PROPERTY}, or none when it is unset or blank. */
    public static Optional<WorkerHeartbeatListener> forProperty(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new WorkerHeartbeatListener(Path.of(value.trim())));
    }

    @Override
    public void onSchedulerEvent(SchedulerEventType type) {
        if (type != SchedulerEventType.RAN_EXECUTE_DUE) {
            return;
        }
        try {
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException | RuntimeException e) {
            failures.incrementAndGet();
            if (warned.compareAndSet(false, true)) {
                warnings.incrementAndGet();
                LOG.warn("worker_heartbeat_write_failed: the heartbeat file cannot be written ({})",
                        e.getClass().getSimpleName());
            }
        }
    }

    long failures() {
        return failures.get();
    }

    long warnings() {
        return warnings.get();
    }
}
