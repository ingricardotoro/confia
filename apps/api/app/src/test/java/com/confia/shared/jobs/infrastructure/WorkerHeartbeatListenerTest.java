package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.event.SchedulerListener.SchedulerEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The heartbeat file of the worker (dockerfiles-and-local-compose scenarios CR16, CR17; design.md decision
 * 6): the modification time moves on the event that ends a polling cycle and on nothing else, a write
 * failure never reaches the scheduler, and without a path no listener exists.
 */
class WorkerHeartbeatListenerTest {

    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

    @TempDir
    Path directory;

    @Test
    void theEventThatEndsAPollingCycleCreatesAndRefreshesTheFile() throws Exception {
        Path file = directory.resolve("worker.heartbeat");
        WorkerHeartbeatListener listener = new WorkerHeartbeatListener(file);

        listener.onSchedulerEvent(SchedulerEventType.RAN_EXECUTE_DUE);
        assertThat(file).as("created on the first cycle").exists();
        assertThat(Files.size(file)).as("no content but a date").isZero();

        Files.setLastModifiedTime(file, LONG_AGO);
        listener.onSchedulerEvent(SchedulerEventType.RAN_EXECUTE_DUE);
        assertThat(Files.getLastModifiedTime(file)).isGreaterThan(LONG_AGO);
    }

    @Test
    void everyOtherSchedulerEventLeavesTheFileAlone() throws Exception {
        Path file = directory.resolve("worker.heartbeat");
        Files.createFile(file);
        Files.setLastModifiedTime(file, LONG_AGO);
        WorkerHeartbeatListener listener = new WorkerHeartbeatListener(file);

        for (SchedulerEventType type : SchedulerEventType.values()) {
            if (type != SchedulerEventType.RAN_EXECUTE_DUE) {
                listener.onSchedulerEvent(type);
            }
        }

        assertThat(SchedulerEventType.values()).as("non-vacuous: other events exist").hasSizeGreaterThan(1);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(LONG_AGO);
    }

    @Test
    void aWriteFailureIsLoggedOnceAndNeverPropagates() {
        Path unwritable = directory.resolve("missing-directory").resolve("worker.heartbeat");
        WorkerHeartbeatListener listener = new WorkerHeartbeatListener(unwritable);

        listener.onSchedulerEvent(SchedulerEventType.RAN_EXECUTE_DUE);
        listener.onSchedulerEvent(SchedulerEventType.RAN_EXECUTE_DUE);

        assertThat(listener.failures()).as("both attempts failed").isEqualTo(2);
        assertThat(listener.warnings()).as("but the warning is written once").isEqualTo(1);
        assertThat(unwritable).doesNotExist();
    }

    @Test
    void withoutAPathThereIsNoListener() {
        assertThat(WorkerHeartbeatListener.forProperty(null)).isEmpty();
        assertThat(WorkerHeartbeatListener.forProperty("  ")).isEmpty();
        assertThat(WorkerHeartbeatListener.forProperty("/tmp/confia-worker.heartbeat")).isPresent();
    }
}
