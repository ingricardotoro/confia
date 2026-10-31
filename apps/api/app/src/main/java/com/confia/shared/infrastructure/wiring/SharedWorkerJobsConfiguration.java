package com.confia.shared.infrastructure.wiring;

import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.PurgeExpiredIdempotencyKeys;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The jobs of the {@code shared} module for the worker process (background-jobs-with-db-scheduler
 * design.md decisions 12 and 17). The first version holds the purge of idempotency keys: the store over
 * the {@code SECURITY DEFINER} function, the use case and its recurring job. The chain verifier and the
 * observation of the queue arrive with the tasks that own them. It takes the {@code DSLContext}, the
 * transactional component, the audit writer and the clock from {@code WorkerPlatformConfiguration}, and
 * declares no key, no pepper and no request origin.
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
}
