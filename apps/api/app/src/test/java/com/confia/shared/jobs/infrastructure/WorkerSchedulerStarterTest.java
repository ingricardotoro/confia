package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.kagkarlsson.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle of the worker's single executor (background-jobs-with-db-scheduler design.md
 * decisions 1 and 12; scenario BJ49): it starts when told to, it is stopped when the context closes,
 * and a worker told not to start it (a test property) says so with a {@code WARN}.
 */
class WorkerSchedulerStarterTest {

    @Test
    void itStartsTheSchedulerWhenAutostartIsOn() {
        Scheduler scheduler = mock(Scheduler.class);

        new WorkerSchedulerStarter(scheduler, true).doStart();

        verify(scheduler).start();
    }

    @Test
    void itDoesNotStartTheSchedulerWhenAutostartIsOffAndLeavesAWarning() {
        Scheduler scheduler = mock(Scheduler.class);
        Logger logger = (Logger) LoggerFactory.getLogger(WorkerSchedulerStarter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new WorkerSchedulerStarter(scheduler, false).doStart();
        } finally {
            logger.detachAppender(appender);
        }

        verify(scheduler, never()).start();
        assertThat(appender.list).filteredOn(event ->
                event.getFormattedMessage().contains("jobs_executor_not_started"))
                .as("the warning").isNotEmpty()
                .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    void itStopsTheSchedulerWhenTheContextCloses() {
        Scheduler scheduler = mock(Scheduler.class);

        new WorkerSchedulerStarter(scheduler, true).destroy();

        verify(scheduler).stop();
    }
}
