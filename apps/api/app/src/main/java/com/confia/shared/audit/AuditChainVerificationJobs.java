package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The two cadences of the audit-chain verifier, as recurring jobs of the worker (background-jobs-with-db-
 * scheduler design.md decision 19, owner decision D-N2; scenarios AT10 to AT16, AT30 and AT31). For each
 * configured institution, the daily one (03:00) verifies from the start of the previous calendar month and
 * the weekly one (Sunday, 03:30) the whole chain from its genesis row. Neither is retried: a technical
 * failure reschedules the job to its next occurrence like any recurring job, and a divergence is a result of
 * the business, recorded and then raised so the occurrence stays visible as failed.
 *
 * <p>What each result leaves is written <em>after</em> the walk, in a transaction of its own, so a verifier
 * that fails midway leaves no result and the entry of a divergence is never lost with the walk:
 *
 * <ul>
 *   <li>intact: the entry {@value #VERIFIED_ACTION} with the scope and the rows verified, and an {@code INFO}
 *       {@value #VERIFIED_EVENT};
 *   <li>empty: the same entry with zero rows and a {@code WARN} {@value #EMPTY_EVENT}, because a real
 *       institution always has entries, so the result usually means a mistyped identifier;
 *   <li>diverged: the entry {@value #FAILED_ACTION} with the outcome {@code error}, the first divergent id
 *       and the kind of divergence, and an {@code ERROR} {@value #BROKEN_EVENT}, the S1 alert of
 *       docs/03-seguridad.md section 12.4.
 * </ul>
 *
 * <p>The chain is never repaired, and no row is touched. The cadence of an hour against an external anchor is
 * not here: it belongs to the change {@code audit-chain-hourly-anchor-verification}.
 */
public final class AuditChainVerificationJobs {

    public static final String DAILY_TASK = "shared.audit-chain-verification-daily";
    public static final String WEEKLY_TASK = "shared.audit-chain-verification-weekly";

    public static final String VERIFIED_ACTION = "shared.audit.chain_verified";
    public static final String FAILED_ACTION = "shared.audit.chain_verification_failed";

    public static final String VERIFIED_EVENT = "audit_chain_verified";
    public static final String EMPTY_EVENT = "audit_chain_empty_for_configured_institution";
    public static final String BROKEN_EVENT = "audit_chain_broken";

    /** The zone of the institution; Tegucigalpa is the only one in F0. Honduras has no daylight saving time. */
    private static final ZoneId ZONE = ZoneId.of("America/Tegucigalpa");

    /**
     * The spec calls the outcome of a failed verification {@code failure}; the check constraint of the table
     * (V2) admits {@code success}, {@code denied} and {@code error}, and {@code error} is the one that means it.
     */
    static final String FAILED_OUTCOME = "error";

    private static final String ACTOR_KIND_SYSTEM = "system";
    private static final String ACTOR_LABEL = "audit-chain-verification";
    private static final String ENTITY_TYPE = "shared.job_run";

    private final TransactionRunner runner;
    private final AuditChainVerifier verifier;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public AuditChainVerificationJobs(TransactionRunner runner, AuditChainVerifier verifier,
            AuditLogWriter auditLogWriter, OperationalEventLog eventLog, Clock clock) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Every day at 03:00 in Tegucigalpa: from the start of the window to the moment of the run. */
    public RecurringJob daily() {
        return new RecurringJob(DAILY_TASK, new JobCalendar.DailyAt(LocalTime.of(3, 0)),
                (institution, run) -> report(institution, run, "daily",
                        verifier.verifySince(institution, dailyWindowStart(clock))));
    }

    /** Every Sunday at 03:30 in Tegucigalpa: the whole chain from the genesis row. */
    public RecurringJob weekly() {
        return new RecurringJob(WEEKLY_TASK, new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30)),
                (institution, run) -> report(institution, run, "weekly", verifier.verifyChainOf(institution)));
    }

    /**
     * Midnight of the first day of the previous calendar month in Tegucigalpa (owner decision D-N2, scenarios
     * AT23 and AT24): the month of the calendar, recalculated at each run, and never a rolling window of 30 days.
     */
    static Instant dailyWindowStart(Clock clock) {
        return clock.instant().atZone(ZONE).toLocalDate().withDayOfMonth(1).minusMonths(1)
                .atStartOfDay(ZONE).toInstant();
    }

    private void report(InstitutionId institution, RecurringRun run, String scope, AuditChainVerification result) {
        switch (result) {
            case AuditChainVerification.Intact intact -> {
                write(institution, run, VERIFIED_ACTION, "success", verified(scope, intact.verifiedRows()));
                eventLog.info(VERIFIED_EVENT, run.runId(), run.taskName(), institution.value(),
                        details("scope", scope, "verifiedRows", intact.verifiedRows()));
            }
            case AuditChainVerification.Empty _ -> {
                write(institution, run, VERIFIED_ACTION, "success", verified(scope, 0));
                eventLog.warn(EMPTY_EVENT, run.runId(), run.taskName(), institution.value(),
                        details("scope", scope));
            }
            case AuditChainVerification.Diverged diverged -> {
                write(institution, run, FAILED_ACTION, FAILED_OUTCOME, AuditJson.object().text("scope", scope)
                        .number("verifiedRows", diverged.verifiedRows())
                        .number("firstDivergentId", diverged.firstDivergentId())
                        .text("divergence", diverged.divergence().name()).toJson());
                eventLog.error(BROKEN_EVENT, run.runId(), run.taskName(), institution.value(),
                        details("scope", scope, "firstDivergentId", diverged.firstDivergentId(),
                                "divergence", diverged.divergence().name()));
                throw new AuditChainBrokenException(institution, diverged.firstDivergentId());
            }
        }
    }

    /**
     * {@code verifiedRows} is what the verifier walked: the whole chain for {@code weekly}, and for {@code daily} the
     * id walk from the first row at or after the start of the window to the end (see {@link
     * AuditChainVerifier#verifySince}), which can include rows older than the window when {@code occurred_at} is
     * not monotonic in {@code id}.
     */
    private static String verified(String scope, long verifiedRows) {
        return AuditJson.object().text("scope", scope).number("verifiedRows", verifiedRows).toJson();
    }

    /** The entry of the result, in a transaction of its own: the walk has already committed. */
    private void write(InstitutionId institution, RecurringRun run, String action, String outcome,
            String afterValue) {
        AuditEntry entry = new AuditEntry(institution.value(), null, ACTOR_KIND_SYSTEM, ACTOR_LABEL, null, null,
                run.runId(), null, action, ENTITY_TYPE, run.runId().toString(), outcome, null, afterValue, null,
                null);
        runner.execute(run.systemContextFor(institution), () -> {
            auditLogWriter.append(entry);
            return null;
        });
    }

    private static Map<String, Object> details(Object... namesAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            details.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return details;
    }
}
