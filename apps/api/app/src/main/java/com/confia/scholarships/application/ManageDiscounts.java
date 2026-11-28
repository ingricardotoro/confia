package com.confia.scholarships.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountKind;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits, deactivates, reactivates, lists and reads the discounts of the institution of the actor (docs/cortes/
 * scholarships-discounts.md). The institution is the one of the actor, never a member of a request. Nothing is deleted: a discount is
 * deactivated. Once an assignment of a discount was approved the type and the value of its benefit do not change, its validity only
 * shortens and its concepts are only removed (another discount is created); the database refuses it too. An edit locks the row of
 * the discount {@code FOR UPDATE} and asks for the version the editor saw. A concept that is newly covered must exist in the
 * institution and be active.
 */
public final class ManageDiscounts {

    private final TransactionRunner transactionRunner;
    private final DiscountRepository discounts;
    private final DiscountAssignmentRepository assignments;
    private final PaymentConceptDirectory concepts;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageDiscounts(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, PaymentConceptDirectory concepts, AuditLogWriter auditLogWriter,
            Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * What a request asks of a discount, as the text of the API.
     *
     * @param benefitType {@code PERCENTAGE} (with {@code percent}) or {@code FIXED_AMOUNT} (with {@code fixedAmount})
     */
    public record Draft(String name, String benefitType, String percent, String fixedAmount, LocalDate validFrom,
            LocalDate validTo, boolean stackable, Set<UUID> conceptIds) {
    }

    /**
     * Creates a discount, active, at version 0.
     *
     * @param kind {@code campaign} or {@code agreement}
     * @throws com.confia.scholarships.domain.DuplicateDiscountCodeException when the code is in use, whatever its capitals
     * @throws InvalidScholarshipsValueException when a value breaks its rule or a concept does not exist in the institution
     * @throws InactiveReferenceException when a covered concept is inactive
     */
    public DiscountView create(AuthenticatedActor actor, String requestId, String code, String kind, Draft draft) {
        return ScholarshipRequests.naming(() -> doCreate(actor, requestId, code, kind, draft));
    }

    private DiscountView doCreate(AuthenticatedActor actor, String requestId, String code, String kind, Draft draft) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(draft, "draft");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        DiscountKind discountKind = DiscountKind.ofCode(kind).orElseThrow(() -> new InvalidScholarshipsValueException("kind", "invalid"));
        Benefit benefit = Benefit.of(draft.benefitType(), draft.percent(), draft.fixedAmount());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Discount created = Discount.create(UUID.randomUUID(), code, draft.name(), discountKind, benefit, draft.validFrom(),
                    draft.validTo(), draft.conceptIds(), draft.stackable(), actor.accountId(), clock.instant());
            requireCoverable(institution, created.concepts(), Set.of());
            discounts.insert(institution, created);
            auditLogWriter.append(DiscountsAudit.discountCreated(actor, auditRequestId, created));
            return view(institution, created, false);
        });
    }

    /**
     * Edits a discount: its name, benefit, validity, stackable flag and covered concepts; the code, the kind and the active flag stay.
     *
     * @param version the version the editor saw
     * @throws ResourceNotFoundException when it does not exist in the institution of the actor
     * @throws com.confia.scholarships.domain.StaleVersionException when the version is not the current one
     * @throws com.confia.scholarships.domain.DiscountBenefitLockedException when the benefit changes and the discount is in use
     * @throws com.confia.scholarships.domain.DiscountScopeLockedException when it is in use and its validity widens or a concept is added
     */
    public DiscountView update(AuthenticatedActor actor, String requestId, UUID id, long version, Draft draft) {
        return ScholarshipRequests.naming(() -> doUpdate(actor, requestId, id, version, draft));
    }

    private DiscountView doUpdate(AuthenticatedActor actor, String requestId, UUID id, long version, Draft draft) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(draft, "draft");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Benefit benefit = Benefit.of(draft.benefitType(), draft.percent(), draft.fixedAmount());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Discount before = discounts.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            boolean inUse = assignments.inUse(institution, Set.of(id)).contains(id);
            Discount after = before.edit(version, draft.name(), benefit, draft.validFrom(), draft.validTo(), draft.conceptIds(),
                    draft.stackable(), inUse, clock.instant());
            requireCoverable(institution, after.concepts(), before.concepts());
            discounts.replace(institution, after, version);
            auditLogWriter.append(DiscountsAudit.discountUpdated(actor, auditRequestId, before, after));
            return view(institution, after, inUse);
        });
    }

    /** Deactivates the discount: it takes no new assignment and stops applying to the charges issued afterwards. */
    public DiscountView deactivate(AuthenticatedActor actor, String requestId, UUID id) {
        return setActive(actor, requestId, id, false);
    }

    /** Reactivates the discount. */
    public DiscountView reactivate(AuthenticatedActor actor, String requestId, UUID id) {
        return setActive(actor, requestId, id, true);
    }

    private DiscountView setActive(AuthenticatedActor actor, String requestId, UUID id, boolean active) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Discount before = discounts.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            Discount after = before.withActive(active, clock.instant());
            if (after != before) {
                discounts.replace(institution, after, before.version());
                auditLogWriter.append(DiscountsAudit.discountActivation(actor, auditRequestId, before, after));
            }
            return view(institution, after, assignments.inUse(institution, Set.of(id)).contains(id));
        });
    }

    public Page<DiscountView> list(AuthenticatedActor actor, String requestId, DiscountFilter filter, PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.benefitType() != null && !isBenefitType(filter.benefitType())) {
            throw new InvalidScholarshipsValueException();
        }
        if (filter.kind() != null && DiscountKind.ofCode(filter.kind()).isEmpty()) {
            throw new InvalidScholarshipsValueException();
        }
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Page<Discount> found = discounts.list(institution, filter, page);
            Set<UUID> ids = new HashSet<>();
            found.items().forEach(discount -> ids.add(discount.id()));
            Set<UUID> inUse = ids.isEmpty() ? Set.of() : assignments.inUse(institution, ids);
            Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, found.items());
            return found.map(discount -> DiscountView.of(discount, conceptViews(discount, labels), inUse.contains(discount.id())));
        });
    }

    public DiscountView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Discount found = discounts.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            return view(institution, found, assignments.inUse(institution, Set.of(id)).contains(id));
        });
    }

    /** Every concept that is newly covered exists in the institution and is active; the ones already covered are not asked again. */
    private void requireCoverable(InstitutionId institution, Set<UUID> wanted, Set<UUID> alreadyCovered) {
        for (UUID conceptId : wanted) {
            if (alreadyCovered.contains(conceptId)) {
                continue;
            }
            PaymentConceptRef ref = concepts.lockShare(institution, conceptId)
                    .orElseThrow(() -> new InvalidScholarshipsValueException("conceptIds", "not-found"));
            if (!ref.active()) {
                throw new InactiveReferenceException();
            }
        }
    }

    private DiscountView view(InstitutionId institution, Discount discount, boolean inUse) {
        Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, List.of(discount));
        return DiscountView.of(discount, conceptViews(discount, labels), inUse);
    }

    private Map<UUID, PaymentConceptLabel> labelsOf(InstitutionId institution, Collection<Discount> found) {
        Set<UUID> ids = new HashSet<>();
        found.forEach(discount -> ids.addAll(discount.concepts()));
        return ids.isEmpty() ? Map.of() : concepts.labels(institution, ids);
    }

    private static List<DiscountView.ConceptView> conceptViews(Discount discount, Map<UUID, PaymentConceptLabel> labels) {
        return discount.concepts().stream().map(id -> labels.get(id)).filter(Objects::nonNull)
                .map(label -> new DiscountView.ConceptView(label.id(), label.code(), label.name())).toList();
    }

    private static boolean isBenefitType(String value) {
        for (BenefitType type : BenefitType.values()) {
            if (type.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
