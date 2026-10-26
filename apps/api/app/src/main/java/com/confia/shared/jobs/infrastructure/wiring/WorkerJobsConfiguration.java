package com.confia.shared.jobs.infrastructure.wiring;

import com.confia.shared.jobs.infrastructure.ConfiguredInstitutions;
import com.confia.shared.jobs.infrastructure.WorkerSchedulerStarter;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.github.kagkarlsson.scheduler.task.Task;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The executor of the worker process, and the only one in the system (background-jobs-with-db-scheduler
 * design.md decisions 1, 10 and 12; ADR-0016 rule 1): the institutions it acts on, one {@link
 * Scheduler} and the starter that runs it. The administrative and portal processes exclude the whole
 * auto-configuration of the library and never hold a {@code Scheduler}.
 *
 * <p>The {@link Scheduler} is built with the library core, not by its auto-configuration, so that the
 * modules can later declare their jobs with types of the JDK and one class translates them
 * (decision 7). No job is registered yet, so it polls an empty set of known tasks. Its settings come
 * from {@code db-scheduler.*} in {@code application.yml}, bound by the library's {@link
 * DbSchedulerProperties}: {@code threads}, {@code polling-interval}, {@code heartbeat-interval},
 * {@code missed-heartbeats-limit} and {@code shutdown-max-wait}. The library’s own auto-configuration
 * stays active but its {@code Scheduler} and starter are conditional on the absence of these, so the
 * context holds exactly one of each and they are ours.
 *
 * <p>The executor uses the raw {@link DataSource}, never the transaction-aware proxy: it must not
 * join a business transaction. Every handler opens its own with {@code TransactionRunner}. The
 * PostgreSQL dialect is chosen explicitly, so that building the executor does not open a connection
 * to detect the database: the process starts with the database down.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DbSchedulerProperties.class)
public class WorkerJobsConfiguration {

    /** Whether the executor starts; true unless the tests that need a quiet worker say otherwise. */
    static final String AUTOSTART_PROPERTY = "confia.jobs.executor.autostart";

    @Bean
    ConfiguredInstitutions configuredInstitutions(Environment environment) {
        return ConfiguredInstitutions.parse(environment.getProperty(ConfiguredInstitutions.PROPERTY));
    }

    @Bean
    Scheduler scheduler(DbSchedulerProperties properties, DataSource dataSource) {
        return Scheduler.create(dataSource, List.<Task<?>>of())
                .jdbcCustomization(
                        new PostgreSqlJdbcCustomization(false, properties.isAlwaysPersistTimestampInUtc()))
                .threads(properties.getThreads())
                .pollingInterval(properties.getPollingInterval())
                .heartbeatInterval(properties.getHeartbeatInterval())
                .missedHeartbeatsLimit(properties.getMissedHeartbeatsLimit())
                .shutdownMaxWait(properties.getShutdownMaxWait())
                .build();
    }

    @Bean
    WorkerSchedulerStarter workerSchedulerStarter(Scheduler scheduler, Environment environment) {
        return new WorkerSchedulerStarter(scheduler,
                environment.getProperty(AUTOSTART_PROPERTY, Boolean.class, true));
    }
}
