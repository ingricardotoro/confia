package com.confia.charges.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.DuplicatePlanItemException;
import com.confia.charges.domain.InactiveReferenceException;
import com.confia.charges.domain.PlanFrequency;
import com.confia.charges.domain.PlanStatus;
import com.confia.charges.domain.PlanTargetScope;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits, lists and reads the charge plans of the institution of the actor, and adds and removes their items
 * (docs/cortes/charges-plans.md). The institution is the one of the actor, never a member of a request. A plan is never
 * deleted: it is archived. An item is not a financial datum, so it is removed, and only while the plan is a draft.
 *
 * <p>Every change of a plan or of its items locks the row of the plan ({@code SELECT ... FOR UPDATE}), so an activation,
 * an edit and the changes of the items take turns and the activation counts the items that will stay. Adding an item then
 * locks the payment concept in share mode, so it cannot be deactivated before the item is committed. Editing a plan asks
 * for the version the editor saw. Every write leaves an entry in the audit trail in the same transaction.
 */
public final class ManageChargePlans {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "createdAt");

    private final TransactionRunner transactionRunner;
    private final ChargePlanRepository plans;
    private final ChargePlanItemRepository items;
    private final PaymentConceptDirectory concepts;
    private final AcademicStructureDirectory structure;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageChargePlans(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, PaymentConceptDirectory concepts, AcademicStructureDirectory structure,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.items = Objects.requireNonNull(items, "items");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.structure = Objects.requireNonNull(structure, "structure");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ChargePlanView create(AuthenticatedActor actor, String requestId, UUID academicYearId, String name,
            String targetScope, UUID targetRefId, String frequency, int dueDayOfMonth) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = ChargePlan.create(UUID.randomUUID(), academicYearId, name,
                    PlanTargetScope.parse(targetScope), targetRefId, PlanFrequency.parse(frequency), dueDayOfMonth,
                    clock.instant());
            requireReferencesInInstitution(institution, plan);
            plans.insert(institution, plan);
            auditLogWriter.append(ChargesAudit.planCreated(actor, auditRequestId, plan));
            return ChargePlanView.of(plan);
        });
    }

    /**
     * Replaces the name, the frequency, the due day and the status of the plan as a whole.
     *
     * @param version the version the editor read
     */
    public ChargePlanView update(AuthenticatedActor actor, String requestId, UUID id, long version, String name,
            String frequency, int dueDayOfMonth, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        PlanFrequency newFrequency = PlanFrequency.parse(frequency);
        PlanStatus newStatus = PlanStatus.parse(status);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan before = plans.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            long itemCount = items.countByPlan(institution, id);
            ChargePlan after = before.edit(version, name, newFrequency, dueDayOfMonth, newStatus,
                    (int) Math.min(itemCount, Integer.MAX_VALUE), clock.instant());
            plans.update(institution, after, version);
            auditLogWriter.append(ChargesAudit.planUpdated(actor, auditRequestId, before, after));
            return ChargePlanView.of(after);
        });
    }

    public Page<ChargePlanView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            UUID academicYearId, String targetScope, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "name", true);
        ChargePlanFilter filter = new ChargePlanFilter(academicYearId,
                targetScope == null || targetScope.isBlank() ? null : PlanTargetScope.parse(targetScope),
                status == null || status.isBlank() ? null : PlanStatus.parse(status));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> plans.list(institution, request, filter).map(ChargePlanView::of));
    }

    /** The plan with its items. */
    public ChargePlanDetailView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            List<ChargePlanItemView> views = items.findByPlan(institution, id).stream()
                    .map(ChargePlanItemView::of).toList();
            return new ChargePlanDetailView(ChargePlanView.of(plan), views);
        });
    }

    /**
     * Adds a payment concept to a draft plan.
     *
     * @param firstPeriodOn the first day of the first period
     */
    public ChargePlanItemView addItem(AuthenticatedActor actor, String requestId, UUID planId,
            UUID paymentConceptId, int periodsCount, LocalDate firstPeriodOn) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.lockById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            plan.requireDraft();
            // Lock order: the plan, then the concept, so a concept cannot be deactivated while its item is being added.
            PaymentConceptRef concept = concepts.lockShare(institution, paymentConceptId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!concept.active()) {
                throw new InactiveReferenceException();
            }
            ChargePlanItem item = new ChargePlanItem(UUID.randomUUID(), planId, paymentConceptId, periodsCount,
                    firstPeriodOn, clock.instant());
            boolean repeated = items.findByPlan(institution, planId).stream()
                    .anyMatch(existing -> existing.paymentConceptId().equals(paymentConceptId));
            if (repeated) {
                throw new DuplicatePlanItemException();
            }
            items.insert(institution, item);
            auditLogWriter.append(ChargesAudit.itemAdded(actor, auditRequestId, item));
            return ChargePlanItemView.of(item);
        });
    }

    /** Removes an item of a draft plan. */
    public void removeItem(AuthenticatedActor actor, String requestId, UUID planId, UUID itemId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            ChargePlan plan = plans.lockById(institution, planId).orElseThrow(ResourceNotFoundException::new);
            plan.requireDraft();
            ChargePlanItem item = items.find(institution, planId, itemId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!items.delete(institution, planId, itemId)) {
                throw new ResourceNotFoundException();
            }
            auditLogWriter.append(ChargesAudit.itemRemoved(actor, auditRequestId, item));
            return null;
        });
    }

    private void requireReferencesInInstitution(InstitutionId institution, ChargePlan plan) {
        boolean found = structure.academicYearExists(institution, plan.academicYearId());
        if (found) {
            found = switch (plan.targetScope()) {
                case MODALITY -> structure.modalityExists(institution, plan.targetRefId());
                case GRADE -> structure.gradeExists(institution, plan.targetRefId());
                case SECTION -> structure.sectionExists(institution, plan.targetRefId());
            };
        }
        if (!found) {
            throw new ResourceNotFoundException();
        }
    }
}
