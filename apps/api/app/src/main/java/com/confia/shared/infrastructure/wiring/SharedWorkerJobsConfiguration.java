package com.confia.shared.infrastructure.wiring;

import com.confia.shared.audit.AuditChainVerificationJobs;
import com.confia.shared.audit.AuditChainVerifier;
import com.confia.shared.audit.AuditLogReader;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.CanonicalAuditRowSerializer;
import com.confia.shared.audit.DefaultAuditChainVerifier;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.shared.infrastructure.JooqScheduledTaskQueueReader;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.observability.queue.LogQueueMetrics;
import com.confia.shared.observability.queue.QueueMetrics;
import com.confia.shared.observability.queue.QueueObservation;
import com.confia.shared.observability.queue.QueueStateReader;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.PurgeExpiredIdempotencyKeys;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The jobs of the {@code shared} module for the worker process (background-jobs-with-db-scheduler
 * design.md decisions 12, 17 and 19). It holds the purge of idempotency keys (the store over the {@code
 * SECURITY DEFINER} function, the use case and its recurring job) and the audit-chain verification (the
 * reader, the serializer, the verifier and the daily and weekly jobs) and the observation of the queue (decision
 * 20: the reader of {@code scheduled_tasks}, the log adapter and the job that publishes the state every 60
 * seconds). It takes the {@code DSLContext}, the transactional component, the audit writer, the clock and the
 * heartbeat settings of the executor from the environment (it never depends on the scheduling library, which
 * only {@code shared.jobs.infrastructure} may touch), and declares no key, no pepper and no request origin.
 *
 * <p>Like {@code IdentityWorkerConfiguration}, it lives in an {@code infrastructure.wiring} package
 * because its factory method takes a {@code DSLContext} (ADR-0015 rule 4), and it is imported
 * explicitly by {@code WorkerApplication}, with no component scan (ADR-0024). The administrative
 * process never loads it.
 */
@Configuration(proxyBeanMethods = false)
public class SharedWorkerJobsConfiguration {

    @Bean
    IdempotencyRecordStore idempotencyRecordStore(DSLContext dsl) {
        return new JooqIdempotencyRecordStore(dsl);
    }

    @Bean
    PurgeExpiredIdempotencyKeys purgeExpiredIdempotencyKeys(TransactionRunner runner,
            IdempotencyRecordStore store, AuditLogWriter auditLogWriter, Clock clock) {
        return new PurgeExpiredIdempotencyKeys(runner, store, auditLogWriter, new OperationalEventLog(), clock);
    }

    /** Every day at 04:40 in Tegucigalpa, for each configured institution. */
    @Bean
    JobDefinition idempotencyKeyPurgeJob(PurgeExpiredIdempotencyKeys purge) {
        return new RecurringJob(PurgeExpiredIdempotencyKeys.TASK_NAME,
                new JobCalendar.DailyAt(LocalTime.of(4, 40)), purge);
    }

    /**
     * The reader of the queue. A taken task counts as stalled when its last heartbeat is older than the interval of
     * the executor times its limit of missed heartbeats, the same moment at which the library presumes its worker
     * dead. Both come from the {@code db-scheduler.*} keys of the environment, with the defaults of the library when
     * a key is absent; the file sets one minute and six. The recurring names come from the registered definitions
     * at each reading, so no job is missed.
     */
    @Bean
    QueueStateReader scheduledTaskQueueReader(DSLContext dsl, Environment environment,
            ObjectProvider<JobDefinition> definitions) {
        Duration heartbeatLimit = environment
                .getProperty("db-scheduler.heartbeat-interval", Duration.class, Duration.ofMinutes(5))
                .multipliedBy(environment.getProperty("db-scheduler.missed-heartbeats-limit", Integer.class, 6));
        return new JooqScheduledTaskQueueReader(dsl, heartbeatLimit, () -> definitions.orderedStream()
                .filter(RecurringJob.class::isInstance).map(JobDefinition::name).toList());
    }

    /** Until a metrics registry exists (P8), the queue is reported as structured log events. */
    @Bean
    QueueMetrics queueMetrics() {
        return new LogQueueMetrics();
    }

    @Bean
    QueueObservation queueObservation(TransactionRunner runner, QueueStateReader scheduledTaskQueueReader,
            QueueMetrics queueMetrics, Clock clock) {
        return new QueueObservation(runner, scheduledTaskQueueReader, queueMetrics, clock);
    }

    /** Every 60 seconds, once for each occurrence, whatever the number of institutions. */
    @Bean
    JobDefinition queueObservationJob(QueueObservation observation) {
        return observation.job();
    }

    @Bean
    AuditLogReader auditLogReader(DSLContext dsl) {
        return new JooqAuditLogReader(dsl);
    }

    @Bean
    CanonicalAuditRowSerializer canonicalAuditRowSerializer() {
        return new CanonicalAuditRowSerializer();
    }

    @Bean
    AuditChainVerifier auditChainVerifier(TransactionRunner runner, AuditLogReader reader,
            CanonicalAuditRowSerializer serializer) {
        return new DefaultAuditChainVerifier(runner, reader, serializer);
    }

    @Bean
    AuditChainVerificationJobs auditChainVerificationJobs(TransactionRunner runner, AuditChainVerifier verifier,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new AuditChainVerificationJobs(runner, verifier, auditLogWriter, new OperationalEventLog(), clock);
    }

    /** Every day at 03:00 in Tegucigalpa, from the start of the previous month, for each configured institution. */
    @Bean
    JobDefinition auditChainDailyVerificationJob(AuditChainVerificationJobs jobs) {
        return jobs.daily();
    }

    /** Every Sunday at 03:30 in Tegucigalpa, over the whole chain, for each configured institution. */
    @Bean
    JobDefinition auditChainWeeklyVerificationJob(AuditChainVerificationJobs jobs) {
        return jobs.weekly();
    }
}
