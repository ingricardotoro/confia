package com.confia.shared.web.authorization;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.web.authorization.DenialWindows.Decision;
import com.confia.shared.web.authorization.DenialWindows.Key;
import com.confia.shared.web.authorization.DenialWindows.PendingSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;

/**
 * The audit of the denials by permission (rbac-permission-matrix-and-audit-integration design.md D9
 * and D10). A denial is counted by {@link DenialWindows} first, outside any transaction, so a retry of
 * the runner never counts twice; a full row is then written in a transaction of its own with the
 * context of the actor. A denial over the cap writes nothing here: it is counted, and the summary of its
 * window is written later by a denial of any key, at most one per denial, or at shutdown.
 *
 * <p>{@link #record} never throws. A write that fails increments {@code authorization_denial_audit_failed}
 * once, gives the denial back to the window so the sum of rows and counts is kept, and says nothing of
 * the failure: its message could repeat a value.
 *
 * <p>The audit entries hold identifiers, the permission code and the route template: never an email,
 * a path, a query, a body, a header or a token. The summary is written with the context of the
 * institution of its window and with the request origin bound again to one without address or agent, so
 * the request that happened to fire it cannot be blamed for it.
 */
public final class PermissionDenialAudit implements PermissionDenialRecorder, DisposableBean {

    static final String ACTION = "authorization.access.denied";
    static final String SUMMARY_ACTION = "authorization.access.denied_summary";
    static final String ENTITY_TYPE = "authorization.permission";
    private static final String OUTCOME = "denied";
    private static final String STAFF = "staff";
    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(10);

    private final TransactionRunner runner;
    private final AuditLogWriter writer;
    private final DenialWindows windows;
    private final AuthorizationMetrics metrics;
    private final Clock clock;
    private final long windowSeconds;

    public PermissionDenialAudit(TransactionRunner runner, AuditLogWriter writer,
            DenialWindows windows, AuthorizationMetrics metrics, Clock clock, Duration window) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.windows = Objects.requireNonNull(windows, "windows");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.windowSeconds = Objects.requireNonNull(window, "window").toSeconds();
    }

    @Override
    public void record(PermissionDeniedException denied, String requestId) {
        Instant now = clock.instant();
        AuthenticatedActor actor = denied.actor();
        Key key = new Key(actor.institutionId(), actor.accountId(), denied.permission());
        Decision decision = null;
        try {
            decision = windows.record(key, now);
            switch (decision.kind()) {
                case OMITTED -> metrics.denialOmitted(denied.permission());
                case FULL_ROW -> writeFullRow(denied, requestId);
                case UNTRACKED -> {
                    metrics.denialTrackerFull();
                    writeFullRow(denied, requestId);
                }
            }
        } catch (RuntimeException failure) {
            metrics.denialAuditFailed();
            giveBack(decision, key, now);
        }
        afterDenial(now);
    }

    private void giveBack(Decision decision, Key key, Instant now) {
        if (decision == null) {
            return;
        }
        switch (decision.kind()) {
            case FULL_ROW -> windows.fullRowFailed(key, decision.windowStart());
            case UNTRACKED -> windows.untrackedRowFailed(key, now);
            case OMITTED -> { }
        }
    }

    private void writeFullRow(PermissionDeniedException denied, String requestId) {
        AuthenticatedActor actor = denied.actor();
        UUID request = requestUuid(requestId);
        AuditEntry entry = new AuditEntry(actor.institutionId(), actor.accountId(), STAFF,
                actor.accountId().toString(), null, null, request, null, ACTION, ENTITY_TYPE,
                denied.permission().code(), OUTCOME, null,
                AuditJson.object().text("routeTemplate", denied.routeTemplate()).toJson(), null,
                null);
        runner.execute(actor.securityContext(request.toString()), () -> {
            writer.append(entry);
            return null;
        });
    }

    /** After a denial: the gated sweep in memory, then at most one summary of the queue. */
    private void afterDenial(Instant now) {
        windows.sweepIfDue(now);
        writeOnePending();
    }

    private void writeOnePending() {
        Optional<PendingSummary> next = windows.pollPending();
        if (next.isEmpty()) {
            return;
        }
        if (!writeSummary(next.get())) {
            metrics.denialAuditFailed();
            if (!windows.requeue(next.get())) {
                metrics.denialAuditFailed();
            }
        }
    }

    private boolean writeSummary(PendingSummary summary) {
        UUID summaryRequestId = UUID.randomUUID();
        Key key = summary.key();
        SecurityContext context = new SecurityContext(key.accountId().toString(), STAFF,
                key.institutionId().toString(), summaryRequestId.toString());
        AuditEntry entry = new AuditEntry(key.institutionId(), key.accountId(), STAFF,
                key.accountId().toString(), null, null, summaryRequestId, null, SUMMARY_ACTION,
                ENTITY_TYPE, key.permission().code(), OUTCOME, null,
                AuditJson.object().number("omittedCount", summary.omittedCount())
                        .instant("windowStart", summary.windowStart())
                        .number("windowSeconds", windowSeconds).toJson(), null, null);
        try {
            ScopedValue.where(RequestOrigin.CURRENT, new RequestOrigin(summaryRequestId, null, null))
                    .run(() -> runner.execute(context, () -> {
                        writer.append(entry);
                        return null;
                    }));
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Writes the summaries still pending before the data source closes: it stops at the first failure,
     * so a database that is down costs one attempt, and when the clock says the budget is spent. Each
     * summary not written counts as a failure of the audit.
     */
    @Override
    public void destroy() {
        Instant start = clock.instant();
        Instant deadline = start.plus(SHUTDOWN_BUDGET);
        List<PendingSummary> summaries = windows.closeAllForShutdown(start);
        int written = 0;
        while (written < summaries.size() && clock.instant().isBefore(deadline)
                && writeSummary(summaries.get(written))) {
            written++;
        }
        for (int unwritten = written; unwritten < summaries.size(); unwritten++) {
            metrics.denialAuditFailed();
        }
    }

    /** For tests only: sweeps with no gate and writes the queue until the first failure. */
    public void flushClosedWindows(Instant now) {
        while (windows.sweepNow(now) > 0) {
            // keep sweeping until nothing closed is left
        }
        Optional<PendingSummary> next = windows.pollPending();
        while (next.isPresent()) {
            if (!writeSummary(next.get())) {
                metrics.denialAuditFailed();
                windows.requeue(next.get());
                return;
            }
            next = windows.pollPending();
        }
    }

    /** For tests only: how many summaries wait. */
    public int pendingSummaryCount() {
        return windows.pendingSize();
    }

    private static UUID requestUuid(String requestId) {
        try {
            return UUID.fromString(requestId);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.randomUUID();
        }
    }
}
