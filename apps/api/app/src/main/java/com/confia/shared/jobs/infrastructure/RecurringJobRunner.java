package com.confia.shared.jobs.infrastructure;

import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs one occurrence of a recurring job for every configured institution
 * (background-jobs-with-db-scheduler design.md decision 10; scenarios BJ30, BJ65 and BJ66). It
 * generates the {@code runId} that is the correlation and the request of everything the occurrence
 * does, puts it in the MDC, and calls the handler once per institution in the configured order. A
 * failure for one institution does not stop the others: it is counted, written as a {@code WARN}
 * {@code job_institution_failed} with the task, the institution and the class of the exception, and at
 * the end a {@link RecurringJobFailedException} makes the library record the occurrence as failed.
 *
 * <p>An occurrence is never retried: the library reschedules a failed recurring task to its next
 * occurrence, and {@code consecutive_failures} stays above zero until a later success resets it.
 */
public final class RecurringJobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RecurringJobRunner.class);

    private final ConfiguredInstitutions institutions;
    private final Clock clock;
    private final Supplier<UUID> runIds;

    public RecurringJobRunner(ConfiguredInstitutions institutions, Clock clock) {
        this(institutions, clock, UUID::randomUUID);
    }

    RecurringJobRunner(ConfiguredInstitutions institutions, Clock clock, Supplier<UUID> runIds) {
        this.institutions = Objects.requireNonNull(institutions, "institutions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIds = Objects.requireNonNull(runIds, "runIds");
    }

    public void run(RecurringJob job) {
        UUID runId = runIds.get();
        RecurringRun run = new RecurringRun(job.name(), runId, clock.instant());
        List<UUID> failed = new ArrayList<>();
        try {
            MDC.put("jobTask", job.name());
            MDC.put("correlationId", runId.toString());
            MDC.put("requestId", runId.toString());
            MDC.put("actorKind", "system");
            for (InstitutionId institution : institutions.institutions()) {
                MDC.put("institutionId", institution.value().toString());
                try {
                    job.handler().runFor(institution, run);
                } catch (RuntimeException e) {
                    failed.add(institution.value());
                    LOG.warn("job_institution_failed task={} institution={} exception={}", job.name(),
                            institution.value(), e.getClass().getName());
                }
            }
        } finally {
            MDC.clear();
        }
        if (!failed.isEmpty()) {
            throw new RecurringJobFailedException(job.name(), failed);
        }
    }
}
