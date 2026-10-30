package com.confia.identity.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.TransactionRunner;
import java.util.Objects;
import java.util.function.IntToLongFunction;

/**
 * The loop and the audit entry both identity purges share (background-jobs-with-db-scheduler design.md
 * decision 17). A purge repeats batches of {@link #BATCH_SIZE} until one comes back short, at most {@link
 * #MAX_BATCHES} of them; each batch is its own transaction, opened with the {@code system} context of the
 * institution and the occurrence, so the batch and its audit entry commit or roll back together. When the
 * batches run out the rest waits for the next occurrence and a warning says so, through {@link
 * OperationalEventLog}: the event, the run, the task and the institution, and nothing else.
 */
final class RetentionPurgeBatches {

    static final int BATCH_SIZE = 5_000;
    static final int MAX_BATCHES = 200;

    static final String BACKLOG_EVENT = "retention_purge_backlog";

    private static final String ACTOR_KIND_SYSTEM = "system";
    private static final String ACTOR_LABEL = "retention-purge";
    private static final String ENTITY_TYPE = "shared.job_run";

    private RetentionPurgeBatches() {
    }

    /**
     * Runs the batches. {@code batch} receives the batch number from 1, runs inside the transaction of
     * that batch, writes the entry of the batch and returns how many rows decide whether it was full.
     */
    static void repeat(TransactionRunner runner, OperationalEventLog eventLog, InstitutionId institution,
            RecurringRun run, IntToLongFunction batch) {
        Objects.requireNonNull(eventLog, "eventLog");
        Objects.requireNonNull(batch, "batch");
        for (int number = 1; number <= MAX_BATCHES; number++) {
            int current = number;
            long rows = runner.execute(run.systemContextFor(institution), () -> batch.applyAsLong(current));
            if (rows < BATCH_SIZE) {
                return;
            }
        }
        eventLog.warn(BACKLOG_EVENT, run.runId(), run.taskName(), institution.value());
    }

    /** The entry of a purge: the system actor, the label of the purge and the occurrence as entity and request. */
    static AuditEntry entry(InstitutionId institution, RecurringRun run, String action, String afterValue) {
        return new AuditEntry(institution.value(), null, ACTOR_KIND_SYSTEM, ACTOR_LABEL, null, null,
                run.runId(), null, action, ENTITY_TYPE, run.runId().toString(), "success", null,
                afterValue, null, null);
    }
}
