package com.confia.identity.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.RecurringJobHandler;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The daily purge of expired password-reset tokens of one institution (background-jobs-with-db-scheduler
 * design.md decision 17; scenarios I256, I263 and I274 to I276). It computes the cutoff once, from its
 * clock, as a day before the occurrence, and deletes in batches through the purge function; each batch
 * writes one audit entry with its count in the same transaction, also when the count is zero. The window
 * is the floor of the function, so the function never deletes a row this class would keep.
 */
public final class PurgeExpiredPasswordResetTokens implements RecurringJobHandler {

    public static final String TASK_NAME = "identity.purge-password-reset-tokens";
    public static final String ACTION = "identity.password_reset_token.purged";

    /** Equal to the floor of {@code identity_purge_password_reset_tokens}: a token is kept for a day after it expires. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final TransactionRunner runner;
    private final IdentityRetentionPurges purges;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public PurgeExpiredPasswordResetTokens(TransactionRunner runner, IdentityRetentionPurges purges,
            AuditLogWriter auditLogWriter, OperationalEventLog eventLog, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.purges = Objects.requireNonNull(purges, "purges");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void runFor(InstitutionId institution, RecurringRun run) {
        Instant cutoff = clock.instant().minus(WINDOW);
        RetentionPurgeBatches.repeat(runner, eventLog, institution, run, batch -> {
            long purged = purges.purgePasswordResetTokens(institution, cutoff,
                    RetentionPurgeBatches.BATCH_SIZE);
            auditLogWriter.append(RetentionPurgeBatches.entry(institution, run, ACTION, AuditJson.object()
                    .number("purged", purged).instant("cutoff", cutoff).number("batch", batch).toJson()));
            return purged;
        });
    }
}
