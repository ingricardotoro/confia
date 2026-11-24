package com.confia.charges.application;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.domain.AccrualErrorCode;
import com.confia.charges.domain.AccrualPeriod;
import com.confia.charges.domain.AccrualPeriods;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StudentPlacement;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The simulator of the charges of a student (docs/cortes/scholarships-awards.md): for a student, a plan and a period it answers, for
 * each concept the plan accrues at that period, what the accrual would produce: the base, the scholarship applied, the net base, the
 * tax and the total. It runs exactly the code of the accrual engine ({@link AccrualEngine}), the one the preview and the run of the
 * worker run, and writes nothing but the audit entry of the access: the data are those of a minor, so every consultation is audited.
 */
public final class SimulateCharges {

    private final TransactionRunner transactionRunner;
    private final ChargePlanRepository plans;
    private final ChargePlanItemRepository items;
    private final StudentDirectory students;
    private final AccrualEngine engine;
    private final AuditLogWriter auditLogWriter;

    public SimulateCharges(TransactionRunner transactionRunner, ChargePlanRepository plans, ChargePlanItemRepository items,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, AwardBenefits awards, AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.items = Objects.requireNonNull(items, "items");
        this.students = Objects.requireNonNull(students, "students");
        this.engine = new AccrualEngine(placements, roster, students, pricing, charges, awards);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    /** One concept of the period and what the engine found for the student. The amounts are present only for a charge to create. */
    public record Line(UUID paymentConceptId, AccrualOutcome outcome, AccrualErrorCode errorCode, Money base, Money benefit,
            Money netBase, UUID scholarshipAwardId, String scholarshipCode, Money tax, Money total, UUID existingChargeId) {

        /** The code of the error as text, or {@code null} when there is none. */
        public String error() {
            return errorCode == null ? null : errorCode.name();
        }
    }

    /** What the accrual of the period would produce for the student, by concept. */
    public record Simulation(UUID planId, UUID studentId, LocalDate periodStart, String periodLabel, List<Line> lines) {

        public Simulation {
            lines = List.copyOf(lines);
        }
    }

    /**
     * @param periodStart the first day of a period of the plan
     * @throws ResourceNotFoundException when the plan or the student does not exist in the institution of the actor, or the student is
     *         not enrolled in the target of the plan in that period
     * @throws InvalidChargesValueException when the date is not the start of a period of the plan
     */
    public Simulation simulate(AuthenticatedActor actor, String requestId, UUID planId, UUID studentId,
            LocalDate periodStart) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(periodStart, "periodStart");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        // Read-write on purpose: the audit entry of the access is written in the same transaction.
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.findById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            students.find(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            List<ChargePlanItem> planItems = items.findByPlan(institution, planId);
            List<AccrualPeriods.PlanPeriod> periods = AccrualPeriods.startingAt(AccrualPeriods.of(plan, planItems),
                    periodStart);
            if (periods.isEmpty()) {
                throw new InvalidChargesValueException();
            }
            AccrualPeriod period = periods.get(0).period();
            StudentPlacement placement = engine.population(institution, plan, period).orElseThrow(ResourceNotFoundException::new)
                    .stream().filter(candidate -> candidate.studentId().equals(studentId)).findFirst()
                    .orElseThrow(ResourceNotFoundException::new);
            List<Line> lines = engine.evaluateStudent(institution, plan, placement, periods, false).stream()
                    .map(SimulateCharges::toLine).toList();
            auditLogWriter.append(ChargesAudit.simulationViewed(actor, auditRequestId, studentId, planId, periodStart));
            return new Simulation(planId, studentId, periodStart, period.label(), lines);
        });
    }

    private static Line toLine(AccrualEngine.Line line) {
        if (line.outcome() != AccrualOutcome.CREATED) {
            return new Line(line.paymentConceptId(), line.outcome(), line.errorCode(), null, null, null, null, null, null,
                    null, line.existingChargeId());
        }
        var charge = line.charge();
        var benefit = charge.benefit();
        return new Line(line.paymentConceptId(), line.outcome(), null, charge.amounts().base(), charge.amounts().discount(),
                charge.netBase(), benefit == null ? null : benefit.awardId(), benefit == null ? null : benefit.scholarshipCode(),
                charge.amounts().tax(), charge.amounts().total(), null);
    }
}
