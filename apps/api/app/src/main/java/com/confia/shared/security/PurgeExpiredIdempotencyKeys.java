package com.confia.shared.security;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.RecurringJobHandler;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The daily purge of idempotency keys that expired more than a day ago, for one institution
 * (background-jobs-with-db-scheduler design.md decisions 16 and 17; scenarios I263, I274 to I276 and AT18
 * to AT21). It computes the cutoff once, from its clock, and deletes in batches through the purge
 * function behind {@link IdempotencyRecordStore#purgeExpired}; each batch writes one audit entry with its
 * count in the same transaction, also when the count is zero. The window is the floor of the function,
 * so the function never deletes a row this class would keep. An expired key that was not purged yet is
 * still reused by updating its row, so the purge changes nothing a client can observe.
 */
public final class PurgeExpiredIdempotencyKeys implements RecurringJobHandler {

    public static final String TASK_NAME = "shared.purge-idempotency-keys";
    public static final String ACTION = "shared.idempotency_key.purged";

    /** Equal to the floor of {@code shared_purge_idempotency_keys}: a key is kept for a day after it expires. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final TransactionRunner runner;
    private final IdempotencyRecordStore store;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public PurgeExpiredIdempotencyKeys(TransactionRunner runner, IdempotencyRecordStore store,
            AuditLogWriter auditLogWriter, OperationalEventLog eventLog, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.store = Objects.requireNonNull(store, "store");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void runFor(InstitutionId institution, RecurringRun run) {
        Instant cutoff = clock.instant().minus(WINDOW);
        RetentionPurgeBatches.repeat(runner, eventLog, institution, run, batch -> {
            long purged = store.purgeExpired(cutoff, RetentionPurgeBatches.BATCH_SIZE);
            auditLogWriter.append(RetentionPurgeBatches.entry(institution, run, ACTION, AuditJson.object()
                    .number("purged", purged).instant("cutoff", cutoff).number("batch", batch).toJson()));
            return purged;
        });
    }
}
