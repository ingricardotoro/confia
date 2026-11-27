package com.confia.charges.application;

import com.confia.charges.domain.AccrualPeriod;
import com.confia.charges.domain.AccrualPeriods;
import com.confia.charges.domain.AccrualRun;
import com.confia.charges.domain.AccrualRunInProgressException;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.IdempotencyKeyRequiredException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.Digests;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The administrative face of the accrual engine (docs/cortes/charges-accrual.md): the periods of a plan, the preview of a
 * period, the request of a run and the reading of runs. Nothing here moves money: the request only creates a QUEUED run and
 * schedules the job that the worker executes ({@link ExecuteAccrualRun}), both in the same transaction, so the job exists
 * if and only if the run does.
 *
 * <p>The key of the request is the {@code Idempotency-Key}, stored permanently on the run with the hash of what it asked: the
 * same key and the same request find the same run, the same key and another request is refused, and another key while a run
 * of the same plan and period is queued or running is a conflict (INV-48). The institution is the one of the actor, never a
 * member of a request.
 */
public final class ManageAccrual {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY = Pattern.compile("[!-~]{1,128}");

    private final TransactionRunner transactionRunner;
    private final ChargePlanRepository plans;
    private final ChargePlanItemRepository items;
    private final AccrualRunRepository runs;
    private final AccrualRunItemRepository runItems;
    private final AccrualPreviewCalculator calculator;
    private final AccrualRunScheduler scheduler;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    /**
     * The accrual with no scholarship ever in force: for a fixture of another capability that does not exercise them. A process
     * wires the constructor that takes the {@link AwardBenefits} that {@code scholarships} publishes.
     */
    public ManageAccrual(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AccrualRunScheduler scheduler, AuditLogWriter auditLogWriter, Clock clock) {
        this(transactionRunner, plans, items, runs, runItems, charges, placements, roster, students, pricing,
                postingAccounts, ledgerAccounts, AwardBenefits.NONE, scheduler, auditLogWriter, clock);
    }

    public ManageAccrual(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AwardBenefits awards, AccrualRunScheduler scheduler, AuditLogWriter auditLogWriter, Clock clock) {
        this(transactionRunner, plans, items, runs, runItems, charges, placements, roster, students, pricing,
                postingAccounts, ledgerAccounts, awards, DiscountBenefits.NONE, scheduler, auditLogWriter, clock);
    }

    public ManageAccrual(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AwardBenefits awards, DiscountBenefits discounts, AccrualRunScheduler scheduler, AuditLogWriter auditLogWriter,
            Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.items = Objects.requireNonNull(items, "items");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.runItems = Objects.requireNonNull(runItems, "runItems");
        this.calculator = new AccrualPreviewCalculator(
                new AccrualEngine(placements, roster, students, pricing, charges, awards, discounts), pricing,
                postingAccounts, ledgerAccounts, awards, discounts);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The periods of the plan that a run can accrue, each with its most recent run. */
    public List<AccrualPeriodView> periods(AuthenticatedActor actor, String requestId, UUID planId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.findById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            List<ChargePlanItem> planItems = items.findByPlan(institution, planId);
            List<AccrualPeriods.PlanPeriod> all = AccrualPeriods.of(plan, planItems);
            Map<LocalDate, AccrualRunView> latest = new HashMap<>();
            for (AccrualRun run : runs.latestByPeriod(institution, planId)) {
                latest.putIfAbsent(run.periodStart(), AccrualRunView.of(run));
            }
            List<AccrualPeriodView> views = new ArrayList<>();
            for (LocalDate start : AccrualPeriods.starts(all)) {
                List<AccrualPeriods.PlanPeriod> starting = AccrualPeriods.startingAt(all, start);
                AccrualPeriod first = starting.get(0).period();
                views.add(new AccrualPeriodView(start, first.label(), first.dueOn(),
                        starting.stream().map(AccrualPeriods.PlanPeriod::paymentConceptId).distinct().toList(),
                        latest.get(start)));
            }
            return views;
        });
    }

    /** What the accrual of the period would do, with nothing written; the blockers say what would make a run refuse. */
    public AccrualPreviewResult preview(AuthenticatedActor actor, String requestId, UUID planId, LocalDate periodStart) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(periodStart, "periodStart");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.findById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            List<ChargePlanItem> planItems = items.findByPlan(institution, planId);
            requirePeriodOfPlan(plan, planItems, periodStart);
            AccrualPreviewCalculator.Calculation calculation = calculator.calculate(institution, plan, planItems,
                    periodStart, false);
            return calculator.preview(planId, periodStart, calculation);
        });
    }

    /**
     * Queues the accrual of the period and schedules the job that executes it.
     *
     * @param idempotencyKey the {@code Idempotency-Key} of the request, from 1 to 128 visible characters
     * @param previewDigest the digest the preview answered, or {@code null} to run without comparing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws AccrualRunInProgressException when another run of the plan and period is queued or running
     */
    public AccrualRunView request(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID planId,
            LocalDate periodStart, String previewDigest) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(periodStart, "periodStart");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!KEY.matcher(idempotencyKey).matches()
                || (previewDigest != null && !DIGEST.matcher(previewDigest).matches())) {
            throw new InvalidChargesValueException();
        }
        String hash = hashOf(planId, periodStart, previewDigest);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return queue(actor, auditRequestId, institution, idempotencyKey, planId, periodStart, previewDigest, hash);
        } catch (AccrualRunRepository.RequestKeyTakenException | AccrualRunInProgressException lostTheRace) {
            // Another request committed first. With the same key its run is the answer; with another key the second
            // pass finds the run in progress and refuses. The unique index that fired first decides which exception
            // arrives here, so both are handled the same way.
            return queue(actor, auditRequestId, institution, idempotencyKey, planId, periodStart, previewDigest, hash);
        }
    }

    private AccrualRunView queue(AuthenticatedActor actor, UUID requestId, InstitutionId institution, String key,
            UUID planId, LocalDate periodStart, String previewDigest, String hash) {
        return transactionRunner.execute(actor.securityContext(requestId.toString()), () -> {
            var existing = runs.findByRequestKey(institution, key);
            if (existing.isPresent()) {
                if (!existing.get().requestHash().equals(hash)) {
                    throw new IdempotencyPayloadMismatchException();
                }
                return AccrualRunView.of(existing.get());
            }
            ChargePlan plan = plans.findById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            requirePeriodOfPlan(plan, items.findByPlan(institution, planId), periodStart);
            if (runs.findActive(institution, planId, periodStart).isPresent()) {
                throw new AccrualRunInProgressException();
            }
            AccrualRun run = AccrualRun.queue(UUID.randomUUID(), planId, periodStart, key, hash, previewDigest,
                    actor.accountId(), clock.instant());
            runs.insert(institution, run);
            auditLogWriter.append(AccrualAudit.requested(actor, requestId, run));
            scheduler.schedule(institution, run.id(), requestId, actor.accountId());
            return AccrualRunView.of(run);
        });
    }

    /** The run with one page of its items. */
    public AccrualRunDetailView get(AuthenticatedActor actor, String requestId, UUID runId, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        if (page < 0 || size < 1 || size > PageRequest.MAX_SIZE) {
            throw new InvalidChargesValueException();
        }
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            AccrualRun run = runs.findById(institution, runId).orElseThrow(ResourceNotFoundException::new);
            return new AccrualRunDetailView(AccrualRunView.of(run), runItems.page(institution, runId, page, size));
        });
    }

    /** The runs of the institution, most recent first, optionally of one plan. */
    public Page<AccrualRunView> list(AuthenticatedActor actor, String requestId, UUID planId, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        if (page < 0 || size < 1 || size > PageRequest.MAX_SIZE) {
            throw new InvalidChargesValueException();
        }
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> runs.list(institution, planId, page, size).map(AccrualRunView::of));
    }

    private static void requirePeriodOfPlan(ChargePlan plan, List<ChargePlanItem> planItems, LocalDate periodStart) {
        if (AccrualPeriods.startingAt(AccrualPeriods.of(plan, planItems), periodStart).isEmpty()) {
            throw new InvalidChargesValueException();
        }
    }

    static String hashOf(UUID planId, LocalDate periodStart, String previewDigest) {
        String text = "v1|" + planId + "|" + periodStart + "|" + (previewDigest == null ? "-" : previewDigest);
        return HexFormat.of().formatHex(Digests.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
}
