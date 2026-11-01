package com.confia.identity.application;

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
 * The daily purge of idle backoff rows of one institution, for the login backoff and for the TOTP backoff
 * (background-jobs-with-db-scheduler design.md decisions 16 and 17; scenarios I262, I263 and I274 to I276).
 * One class serves both kinds because the rule is the same: a row whose last attempt is more than a day old
 * is deleted, in batches through the purge function, and each batch writes one audit entry with its count in
 * the same transaction, also when the count is zero. A row that is purged is simply a first failure again
 * the next time its identifier or account fails, so the purge changes nothing a user can observe.
 */
public final class PurgeStaleBackoff implements RecurringJobHandler {

    /** Which backoff table a handler purges: the name of its task, the action of its entries and its operation. */
    public enum Kind {
        LOGIN("identity.purge-login-backoff", "identity.login_backoff.purged"),
        MFA_TOTP("identity.purge-mfa-totp-backoff", "identity.mfa_totp_backoff.purged");

        private final String taskName;
        private final String action;

        Kind(String taskName, String action) {
            this.taskName = taskName;
            this.action = action;
        }

        public String taskName() {
            return taskName;
        }

        public String action() {
            return action;
        }

        long purge(IdentityRetentionPurges purges, Instant cutoff, int limit) {
            return this == LOGIN ? purges.purgeLoginBackoff(cutoff, limit)
                    : purges.purgeMfaTotpBackoff(cutoff, limit);
        }
    }

    /** Equal to the floor of the two functions: a row is kept for a day after its last attempt. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final Kind kind;
    private final TransactionRunner runner;
    private final IdentityRetentionPurges purges;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public PurgeStaleBackoff(Kind kind, TransactionRunner runner, IdentityRetentionPurges purges,
            AuditLogWriter auditLogWriter, OperationalEventLog eventLog, Clock clock) {
        this.kind = Objects.requireNonNull(kind, "kind");
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
            long purged = kind.purge(purges, cutoff, RetentionPurgeBatches.BATCH_SIZE);
            auditLogWriter.append(RetentionPurgeBatches.entry(institution, run, kind.action, AuditJson.object()
                    .number("purged", purged).instant("cutoff", cutoff).number("batch", batch).toJson()));
            return purged;
        });
    }
}
