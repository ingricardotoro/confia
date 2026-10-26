package com.confia.shared.jobs.infrastructure;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Starts the single {@link Scheduler} of the worker when the context is ready and stops it when the
 * context closes (background-jobs-with-db-scheduler design.md decisions 1 and 12). It implements the
 * starter's own {@link DbSchedulerStarter}, so the library's auto-configuration retires the starter it
 * would declare ({@code @ConditionalOnMissingBean}) and nothing else starts the executor.
 *
 * <p>Starting on {@link ApplicationReadyEvent} means no task runs before the context is complete. The
 * executor is stopped through {@link #destroy()}, which waits at most the configured {@code
 * shutdown-max-wait} for a running task, so closing the container or a test never blocks for the
 * library's 30-minute default.
 *
 * <p>The property {@code confia.jobs.executor.autostart} (true unless set) exists only so the tests
 * that start the real worker do not run a polling thread. When it is false the starter writes a
 * {@code WARN} {@code jobs_executor_not_started} and starts nothing; {@code JobsConfigurationGuardTest}
 * keeps it out of every versioned configuration file.
 */
public final class WorkerSchedulerStarter implements DbSchedulerStarter, DisposableBean,
        ApplicationListener<ApplicationReadyEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerSchedulerStarter.class);

    private final Scheduler scheduler;
    private final boolean autostart;

    public WorkerSchedulerStarter(Scheduler scheduler, boolean autostart) {
        this.scheduler = scheduler;
        this.autostart = autostart;
    }

    @Override
    public void doStart() {
        if (!autostart) {
            LOG.warn("jobs_executor_not_started: confia.jobs.executor.autostart is false, "
                    + "so this worker runs no task");
            return;
        }
        scheduler.start();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        doStart();
    }

    @Override
    public void destroy() {
        scheduler.stop();
    }
}
