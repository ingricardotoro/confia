package com.confia.identity.application;

import com.confia.identity.domain.SessionLifetimePolicy;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.RecurringJobHandler;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.RetentionPurgeBatches;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The daily purge of refresh tokens of one institution (background-jobs-with-db-scheduler design.md
 * decision 17; scenarios I252 to I256, I262, I263 and I274 to I276). First, in a transaction of its own
 * with its own audit entry, it empties the encrypted successor of every token consumed more than the
 * grace window ago: the row stays, so reuse detection is unchanged. Then it deletes the families that
 * were revoked, or reached their absolute end, more than thirty days ago, with all their tokens, in
 * batches through the purge function; each batch writes one audit entry with its counts in the same
 * transaction, also when they are zero.
 */
public final class PurgeExpiredRefreshTokens implements RecurringJobHandler {

    public static final String TASK_NAME = "identity.purge-refresh-tokens";
    public static final String ACTION_CLEARED = "identity.refresh_token.successors_cleared";
    public static final String ACTION_PURGED = "identity.refresh_token.purged";

    /** Equal to the floor of {@code identity_purge_refresh_token_families}: a family is kept for thirty days after it ends. */
    static final Duration WINDOW = Duration.ofDays(30);

    private final TransactionRunner runner;
    private final IdentityRetentionPurges purges;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public PurgeExpiredRefreshTokens(TransactionRunner runner, IdentityRetentionPurges purges,
            AuditLogWriter auditLogWriter, OperationalEventLog eventLog, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.purges = Objects.requireNonNull(purges, "purges");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void runFor(InstitutionId institution, RecurringRun run) {
        Instant now = clock.instant();
        clearResidualSuccessors(institution, run, now.minus(SessionLifetimePolicy.GRACE_WINDOW));
        Instant cutoff = now.minus(WINDOW);
        RetentionPurgeBatches.repeat(runner, eventLog, institution, run, batch -> {
            RefreshTokenPurgeCount purged = purges.purgeRefreshTokenFamilies(institution, cutoff,
                    RetentionPurgeBatches.BATCH_SIZE);
            auditLogWriter.append(RetentionPurgeBatches.entry(institution, run, ACTION_PURGED, AuditJson.object()
                    .number("families", purged.families()).number("tokens", purged.tokens())
                    .instant("cutoff", cutoff).number("batch", batch).toJson()));
            return purged.families();
        });
    }

    private void clearResidualSuccessors(InstitutionId institution, RecurringRun run, Instant cutoff) {
        runner.execute(run.systemContextFor(institution), () -> {
            long cleared = purges.clearResidualSuccessors(institution, cutoff);
            auditLogWriter.append(RetentionPurgeBatches.entry(institution, run, ACTION_CLEARED, AuditJson.object()
                    .number("cleared", cleared).instant("cutoff", cutoff).toJson()));
            return cleared;
        });
    }
}
