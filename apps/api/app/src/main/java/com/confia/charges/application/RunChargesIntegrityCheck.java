package com.confia.charges.application;

import com.confia.charges.domain.IntegrityCheck;
import com.confia.charges.domain.IntegrityDiscrepanciesFoundException;
import com.confia.charges.domain.IntegrityRun;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerIntegrityApi;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.jobs.RecurringJobHandler;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The nightly integrity check of the charges against the ledger, for one institution (docs/cortes/charges-void-and-integrity.md;
 * ADR-0016). It is a recurring job of the worker, at 02:30 in Tegucigalpa, once for each configured institution, with the
 * {@code system} actor and the institution of the occurrence. It carries no data and is never retried: a technical failure
 * reschedules it to its next occurrence like any recurring job.
 *
 * <p>The comparison is {@link IntegrityCheck}, a pure function. This class only reads, in read-only transactions, and records.
 * The reads are not one snapshot (the isolation is {@code READ COMMITTED}; {@code SERIALIZABLE} is reserved by ADR-0010), so a
 * first reading that finds something is repeated and only what both readings agree on is recorded: an operation that committed
 * between the reads of the first one cannot raise an alarm. The result is written afterwards in a transaction of its own, with
 * its audit entry, so a reader that fails midway leaves no result. A confirmed discrepancy is recorded, announced with an {@code
 * ERROR} event and then raised, so the occurrence stays visible as failed until a later clean run. Nothing is ever repaired.
 */
public final class RunChargesIntegrityCheck implements RecurringJobHandler {

    public static final String TASK_NAME = "charges.integrity-check";
    public static final String FOUND_EVENT = "charges_integrity_discrepancies_found";
    public static final String CLEAN_EVENT = "charges_integrity_ok";

    static final String SOURCE_MODULE = "charges";
    private static final List<String> RECEIVABLE_KINDS = List.of(IntegrityCheck.CHARGE_KIND,
            IntegrityCheck.REVERSAL_KIND);

    private final TransactionRunner transactionRunner;
    private final ChargeRepository charges;
    private final LedgerIntegrityApi ledger;
    private final IntegrityRunRepository runs;
    private final AuditLogWriter auditLogWriter;
    private final OperationalEventLog eventLog;
    private final Clock clock;

    public RunChargesIntegrityCheck(TransactionRunner transactionRunner, ChargeRepository charges,
            LedgerIntegrityApi ledger, IntegrityRunRepository runs, AuditLogWriter auditLogWriter,
            OperationalEventLog eventLog, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Every day at 02:30 in Tegucigalpa, before the audit chain verification, for each configured institution. */
    public RecurringJob job() {
        return new RecurringJob(TASK_NAME, new JobCalendar.DailyAt(LocalTime.of(2, 30)), this);
    }

    @Override
    public void runFor(InstitutionId institution, RecurringRun run) {
        Objects.requireNonNull(institution, "institution");
        Objects.requireNonNull(run, "run");
        SecurityContext context = run.systemContextFor(institution);
        Instant startedAt = clock.instant();
        IntegrityCheck.Outcome outcome = transactionRunner.executeReadOnly(context, () -> read(institution));
        if (!outcome.sound()) {
            IntegrityCheck.Outcome second = transactionRunner.executeReadOnly(context, () -> read(institution));
            outcome = IntegrityCheck.confirmed(outcome, second);
        }
        IntegrityRun result = new IntegrityRun(run.runId(), startedAt, clock.instant(), outcome.chargesChecked(),
                outcome.postingsChecked(), outcome.findings().size());
        IntegrityCheck.Outcome recorded = outcome;
        transactionRunner.execute(context, () -> {
            runs.insert(institution, result, recorded.findings());
            auditLogWriter.append(ChargesAudit.integrityRunCompleted(institution.value(), run.runId(), result,
                    recorded.countsByKind()));
            return null;
        });
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chargesChecked", result.chargesChecked());
        details.put("postingsChecked", result.postingsChecked());
        details.put("discrepancies", result.discrepancyCount());
        if (result.discrepancyCount() == 0) {
            eventLog.info(CLEAN_EVENT, run.runId(), run.taskName(), institution.value(), details);
            return;
        }
        eventLog.error(FOUND_EVENT, run.runId(), run.taskName(), institution.value(), details);
        throw new IntegrityDiscrepanciesFoundException(institution, result.discrepancyCount());
    }

    /** One reading of the charges and of the ledger, and the comparison. */
    private IntegrityCheck.Outcome read(InstitutionId institution) {
        List<IntegrityCheck.ChargeFact> chargeFacts = charges.integrityFacts(institution);
        List<IntegrityCheck.PostingFact> postings = ledger.postingsOfSource(institution, SOURCE_MODULE).stream()
                .map(posting -> new IntegrityCheck.PostingFact(posting.transactionId(), posting.kind(),
                        posting.sourceRefId(), posting.reversesTransactionId(), posting.debitTotal(),
                        posting.creditTotal()))
                .toList();
        List<IntegrityCheck.UnbalancedFact> unbalanced = ledger.unbalancedTransactions(institution).stream()
                .map(each -> new IntegrityCheck.UnbalancedFact(each.transactionId(), each.debitTotal(),
                        each.creditTotal()))
                .toList();
        List<IntegrityCheck.MovementFact> movements = ledger
                .receivableMovementOfSource(institution, SOURCE_MODULE, RECEIVABLE_KINDS).stream()
                .map(each -> new IntegrityCheck.MovementFact(each.currency(), each.debit(), each.credit()))
                .toList();
        return IntegrityCheck.evaluate(new IntegrityCheck.Reading(chargeFacts, postings, unbalanced, movements));
    }
}
