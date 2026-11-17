package com.confia.charges.application;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.domain.AccrualCounters;
import com.confia.charges.domain.AccrualErrorCode;
import com.confia.charges.domain.AccrualPeriods;
import com.confia.charges.domain.AccrualRejection;
import com.confia.charges.domain.AccrualRun;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.ComputedCharge;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.RunStatus;
import com.confia.charges.domain.StudentPlacement;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Executes an accrual run in the worker (docs/cortes/charges-accrual.md, atomicity). Three phases, each in its own database
 * transaction opened by the transactional component with the {@code system} context of the institution of the job:
 *
 * <ol>
 *   <li><b>Begin.</b> Locks the run. A finished run is left alone (the job is delivered at least once). A queued run is
 *       checked against its preconditions (plan active, period in the plan and not in the future in the time zone of the
 *       institution, accounts of the map, target inside the year, and the digest of the preview when it carries one): a
 *       failed one is REJECTED with nothing written; otherwise it is started. A run that is already running is the
 *       resumption after a crash and skips what it did.
 *   <li><b>One transaction per student.</b> Reads the price and the tax rate {@code FOR SHARE}, locks the sub-accounts of the
 *       student in the order student, currency, reads again under the lock what is already accrued, posts the ledger
 *       transaction of each valid charge and then inserts the charge, and records the items of the student. A failure of the
 *       database rolls the student back whole, and the student is recorded as {@code POSTING_FAILED}; a deadlock is repeated
 *       by the transactional component. The unit is the student and the unit of error is the charge.
 *   <li><b>Finish.</b> Counts the items, writes the counters and the final status, and audits it with the exact totals.
 * </ol>
 *
 * <p>Idempotent: running it again on a finished run does nothing, and on a running one creates only what is missing.
 */
public final class ExecuteAccrualRun {

    private static final String SOURCE_MODULE = "charges";
    private static final String SYSTEM = "system";

    private final TransactionRunner transactionRunner;
    private final ChargePlanRepository plans;
    private final ChargePlanItemRepository planItems;
    private final AccrualRunRepository runs;
    private final AccrualRunItemRepository runItems;
    private final ChargeRepository charges;
    private final PostingAccountRepository postingAccounts;
    private final LedgerPostingApi ledger;
    private final PlacementDirectory placements;
    private final AccrualEngine engine;
    private final AccrualPreviewCalculator calculator;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ExecuteAccrualRun(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository planItems, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            LedgerPostingApi ledger, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.planItems = Objects.requireNonNull(planItems, "planItems");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.runItems = Objects.requireNonNull(runItems, "runItems");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.postingAccounts = Objects.requireNonNull(postingAccounts, "postingAccounts");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.engine = new AccrualEngine(placements, roster, students, pricing, charges);
        this.calculator = new AccrualPreviewCalculator(engine, pricing, postingAccounts, ledgerAccounts);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the first phase decided: nothing more to do, or the plan, the periods and the students to process. */
    private record Begin(boolean proceed, UUID requestedBy, ChargePlan plan,
            List<AccrualPeriods.PlanPeriod> periods, List<StudentPlacement> population) {

        static Begin done() {
            return new Begin(false, null, null, List.of(), List.of());
        }
    }

    /**
     * Runs the accrual.
     *
     * @param context the {@code system} security context of the institution of the job
     * @throws ResourceNotFoundException when the run does not exist in the institution of the job
     */
    public void execute(SecurityContext context, UUID runId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(runId, "runId");
        InstitutionId institution = new InstitutionId(UUID.fromString(context.institutionId()));
        UUID requestId = UUID.fromString(context.requestId());
        Begin begin = transactionRunner.execute(context, () -> begin(institution, requestId, runId));
        if (!begin.proceed()) {
            return;
        }
        Set<UUID> done = transactionRunner.executeReadOnly(context, () -> runItems.processedStudents(institution, runId));
        for (StudentPlacement student : begin.population()) {
            if (done.contains(student.studentId())) {
                continue;
            }
            processStudent(context, institution, requestId, runId, begin, student);
        }
        transactionRunner.execute(context, () -> finish(institution, requestId, runId));
    }

    /** The last attempt of the job failed: the run is marked failed so that nobody waits for it, and nothing is lost. */
    public void markFailed(SecurityContext context, UUID runId) {
        InstitutionId institution = new InstitutionId(UUID.fromString(context.institutionId()));
        UUID requestId = UUID.fromString(context.requestId());
        transactionRunner.execute(context, () -> {
            Optional<AccrualRun> found = runs.lockById(institution, runId);
            if (found.isEmpty() || found.get().status().isTerminal()) {
                return null;
            }
            AccrualRun failed = found.get().fail(AccrualRejection.RETRIES_EXHAUSTED, clock.instant());
            runs.update(institution, failed);
            auditLogWriter.append(AccrualAudit.finished(institution.value(), requestId, failed, List.of()));
            return null;
        });
    }

    private Begin begin(InstitutionId institution, UUID requestId, UUID runId) {
        AccrualRun run = runs.lockById(institution, runId).orElseThrow(ResourceNotFoundException::new);
        if (run.status().isTerminal()) {
            return Begin.done();
        }
        ChargePlan plan = plans.findById(institution, run.planId()).orElseThrow(ResourceNotFoundException::new);
        List<ChargePlanItem> items = planItems.findByPlan(institution, run.planId());
        List<AccrualPeriods.PlanPeriod> periods = AccrualPeriods.startingAt(AccrualPeriods.of(plan, items),
                run.periodStart());
        if (run.status() == RunStatus.QUEUED) {
            Optional<AccrualRejection> rejection = rejectionOf(institution, run, plan, items, periods);
            if (rejection.isPresent()) {
                AccrualRun rejected = run.reject(rejection.get(), clock.instant());
                runs.update(institution, rejected);
                auditLogWriter.append(AccrualAudit.finished(institution.value(), requestId, rejected, List.of()));
                return Begin.done();
            }
            run = run.start(clock.instant());
            runs.update(institution, run);
            auditLogWriter.append(AccrualAudit.started(institution.value(), requestId, run));
        }
        List<StudentPlacement> population = engine.population(institution, plan, periods.get(0).period())
                .orElseThrow(ResourceNotFoundException::new);
        return new Begin(true, run.requestedBy(), plan, periods, population);
    }

    private Optional<AccrualRejection> rejectionOf(InstitutionId institution, AccrualRun run, ChargePlan plan,
            List<ChargePlanItem> items, List<AccrualPeriods.PlanPeriod> periods) {
        if (periods.isEmpty()) {
            return Optional.of(AccrualRejection.PERIOD_NOT_IN_PLAN);
        }
        List<AccrualRejection> blockers = calculator.preconditions(institution, plan, periods);
        if (!blockers.isEmpty()) {
            return Optional.of(blockers.get(0));
        }
        ZoneId zone = placements.timezoneOf(institution).orElse(ZoneId.of("America/Tegucigalpa"));
        if (run.periodStart().isAfter(LocalDate.now(clock.withZone(zone)))) {
            return Optional.of(AccrualRejection.PERIOD_IN_FUTURE);
        }
        if (engine.population(institution, plan, periods.get(0).period()).isEmpty()) {
            return Optional.of(AccrualRejection.PLAN_TARGET_OUTSIDE_YEAR);
        }
        if (run.previewDigest() != null) {
            String digest = calculator.calculate(institution, plan, items, run.periodStart(), false).digest();
            if (!digest.equals(run.previewDigest())) {
                return Optional.of(AccrualRejection.PREVIEW_OUTDATED);
            }
        }
        return Optional.empty();
    }

    private void processStudent(SecurityContext context, InstitutionId institution, UUID requestId, UUID runId,
            Begin begin, StudentPlacement student) {
        try {
            transactionRunner.execute(context, () -> {
                unitOfStudent(institution, requestId, runId, begin, student);
                return null;
            });
        } catch (RuntimeException failure) {
            // The unit rolled back whole. Nothing of this student exists; record why, in a transaction of its own. If
            // even that fails the exception reaches the job, which is retried and resumes from the students it finished.
            transactionRunner.execute(context, () -> {
                List<RunItem> errors = new ArrayList<>();
                for (AccrualPeriods.PlanPeriod period : begin.periods()) {
                    errors.add(RunItem.error(student.studentId(), period.paymentConceptId(),
                            AccrualErrorCode.POSTING_FAILED));
                }
                runItems.insertAll(institution, runId, errors);
                return null;
            });
        }
    }

    /** One student, in the transaction of the caller. */
    private void unitOfStudent(InstitutionId institution, UUID requestId, UUID runId, Begin begin,
            StudentPlacement student) {
        LedgerPostingApi.Actor actor = new LedgerPostingApi.Actor(institution.value(), begin.requestedBy(), SYSTEM,
                requestId);
        List<AccrualEngine.Line> lines = new ArrayList<>(
                engine.evaluateStudent(institution, begin.plan(), student, begin.periods(), true));

        Set<LedgerPostingApi.AccountKey> keys = new java.util.TreeSet<>(
                java.util.Comparator.comparing(LedgerPostingApi.AccountKey::studentId)
                        .thenComparing(LedgerPostingApi.AccountKey::currency));
        for (AccrualEngine.Line line : lines) {
            if (line.outcome() == AccrualOutcome.CREATED) {
                keys.add(new LedgerPostingApi.AccountKey(student.studentId(), line.charge().amounts().base().currency()));
            }
        }
        Map<CurrencyCode, LedgerPostingApi.LockedAccount> locked = new HashMap<>();
        if (!keys.isEmpty()) {
            for (LedgerPostingApi.LockedAccount account : ledger.lockStudentAccounts(actor, keys)) {
                locked.put(account.key().currency(), account);
            }
        }
        PostingAccountMap map = new PostingAccountMap(postingAccounts.list(institution));
        List<RunItem> items = new ArrayList<>();
        for (AccrualEngine.Line line : lines) {
            if (line.outcome() != AccrualOutcome.CREATED) {
                items.add(toItem(line, null));
                continue;
            }
            ComputedCharge charge = line.charge();
            // Under the lock of the sub-account: what another run committed meanwhile is read now, not before.
            Optional<AccrualEngine.Line> already = AccrualEngine.alreadyAccrued(institution, begin.plan().id(),
                    student.studentId(), charge.paymentConceptId(), charge.period(), charges.findOverlapping(institution,
                            student.studentId(), charge.paymentConceptId(), charge.period().start(),
                            charge.period().end()));
            if (already.isPresent()) {
                items.add(toItem(already.get(), null));
                continue;
            }
            if (locked.get(charge.amounts().base().currency()).underReview()) {
                items.add(RunItem.error(student.studentId(), charge.paymentConceptId(),
                        AccrualErrorCode.ACCOUNT_UNDER_REVIEW));
                continue;
            }
            UUID chargeId = UUID.randomUUID();
            UUID transactionId = ledger.postCharge(actor, postingOf(map, charge, chargeId)).transactionId();
            charges.insertAutomatic(institution, chargeId, charge, runId, transactionId, begin.requestedBy());
            items.add(RunItem.created(student.studentId(), charge.paymentConceptId(), chargeId));
        }
        runItems.insertAll(institution, runId, items);
    }

    /**
     * The ledger transaction of a charge: debit the receivable of the student for the total, credit the revenue of the
     * category for the base and, when there is tax, credit the tax payable. It balances because the total is the base plus
     * the tax. A discount would be a transaction of its own and is deferred, so a charge with one is refused here.
     */
    private LedgerPostingApi.ChargePosting postingOf(PostingAccountMap map, ComputedCharge charge, UUID chargeId) {
        Money base = charge.amounts().base();
        Money tax = charge.amounts().tax();
        if (!charge.amounts().discount().isZero()) {
            throw new IllegalStateException("a discount is posted by its own transaction and is deferred");
        }
        UUID receivable = map.receivable().orElseThrow(() -> new IllegalStateException("no receivable account"));
        UUID revenue = map.revenueFor(charge.conceptCategory())
                .orElseThrow(() -> new IllegalStateException("no revenue account for the category"));
        List<LedgerPostingApi.Line> lines = new ArrayList<>();
        lines.add(new LedgerPostingApi.Line(receivable, charge.studentId(), LedgerPostingApi.Side.DEBIT,
                charge.amounts().total()));
        lines.add(new LedgerPostingApi.Line(revenue, null, LedgerPostingApi.Side.CREDIT, base));
        if (tax.isPositive()) {
            UUID taxPayable = map.taxPayable().orElseThrow(() -> new IllegalStateException("no tax payable account"));
            lines.add(new LedgerPostingApi.Line(taxPayable, null, LedgerPostingApi.Side.CREDIT, tax));
        }
        return new LedgerPostingApi.ChargePosting(charge.period().start(), SOURCE_MODULE, chargeId,
                "charges:accrual:" + chargeId, lines);
    }

    private static RunItem toItem(AccrualEngine.Line line, UUID chargeId) {
        return switch (line.outcome()) {
            case CREATED -> RunItem.created(line.studentId(), line.paymentConceptId(), chargeId);
            case ALREADY_ACCRUED, ALREADY_ACCRUED_OTHER_PLAN -> RunItem.existing(line.studentId(),
                    line.paymentConceptId(), line.outcome(), line.existingChargeId());
            case ERROR -> RunItem.error(line.studentId(), line.paymentConceptId(), line.errorCode());
        };
    }

    private Void finish(InstitutionId institution, UUID requestId, UUID runId) {
        AccrualRun run = runs.lockById(institution, runId).orElseThrow(ResourceNotFoundException::new);
        if (run.status().isTerminal()) {
            return null;
        }
        AccrualRunItemRepository.RunItemCounts counts = runItems.counts(institution, runId);
        AccrualRun finished = run.complete(
                new AccrualCounters(counts.students(), counts.created(), counts.existing(), counts.failed()),
                clock.instant());
        runs.update(institution, finished);
        Map<String, ChargeRepository.CurrencyTotals> sorted = new TreeMap<>();
        charges.totalsOfRun(institution, runId).forEach(total -> sorted.put(total.currency().name(), total));
        auditLogWriter.append(AccrualAudit.finished(institution.value(), requestId, finished,
                List.copyOf(sorted.values())));
        return null;
    }
}
