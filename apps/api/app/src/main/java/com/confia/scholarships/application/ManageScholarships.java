package com.confia.scholarships.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.BenefitType;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.Scholarship;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits, deactivates, reactivates, lists and reads the scholarships of the institution of the actor (docs/cortes/
 * scholarships-awards.md). The institution is the one of the actor, never a member of a request. Nothing is deleted: a scholarship
 * is deactivated. Once an award of a scholarship was approved the type and the value of its benefit do not change (another
 * scholarship is created); the database refuses it too. An edit locks the row of the scholarship {@code FOR UPDATE} and asks for
 * the version the editor saw. A concept that is newly covered must exist in the institution and be active.
 */
public final class ManageScholarships {

    private final TransactionRunner transactionRunner;
    private final ScholarshipRepository scholarships;
    private final AwardRepository awards;
    private final PaymentConceptDirectory concepts;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageScholarships(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            PaymentConceptDirectory concepts, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.scholarships = Objects.requireNonNull(scholarships, "scholarships");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * What a request asks of a scholarship, as the text of the API.
     *
     * @param benefitType {@code PERCENTAGE} (with {@code percent}) or {@code FIXED_AMOUNT} (with {@code fixedAmount})
     * @param periodCap the optional cap per period, or {@code null}
     */
    public record Draft(String name, String benefitType, String percent, String fixedAmount, String periodCap,
            Set<UUID> conceptIds) {
    }

    /**
     * Creates a scholarship, active, at version 0.
     *
     * @throws com.confia.scholarships.domain.DuplicateScholarshipCodeException when the code is in use, whatever its capitals
     * @throws InvalidScholarshipsValueException when a value breaks its rule or a concept does not exist in the institution
     * @throws InactiveReferenceException when a covered concept is inactive
     */
    public ScholarshipView create(AuthenticatedActor actor, String requestId, String code, Draft draft) {
        return ScholarshipRequests.naming(() -> doCreate(actor, requestId, code, draft));
    }

    private ScholarshipView doCreate(AuthenticatedActor actor, String requestId, String code, Draft draft) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(draft, "draft");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Benefit benefit = Benefit.of(draft.benefitType(), draft.percent(), draft.fixedAmount());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Scholarship created = Scholarship.create(UUID.randomUUID(), code, draft.name(), benefit,
                    Benefit.cap(draft.periodCap()), draft.conceptIds(), actor.accountId(), clock.instant());
            requireCoverable(institution, created.concepts(), Set.of());
            scholarships.insert(institution, created);
            auditLogWriter.append(ScholarshipsAudit.scholarshipCreated(actor, auditRequestId, created));
            return view(institution, created, false);
        });
    }

    /**
     * Edits a scholarship: its name, benefit, cap and covered concepts; the code and the active flag stay.
     *
     * @param version the version the editor saw
     * @throws ResourceNotFoundException when it does not exist in the institution of the actor
     * @throws com.confia.scholarships.domain.StaleVersionException when the version is not the current one
     * @throws com.confia.scholarships.domain.ScholarshipBenefitLockedException when the benefit changes and an award was approved
     */
    public ScholarshipView update(AuthenticatedActor actor, String requestId, UUID id, long version, Draft draft) {
        return ScholarshipRequests.naming(() -> doUpdate(actor, requestId, id, version, draft));
    }

    private ScholarshipView doUpdate(AuthenticatedActor actor, String requestId, UUID id, long version, Draft draft) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(draft, "draft");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Benefit benefit = Benefit.of(draft.benefitType(), draft.percent(), draft.fixedAmount());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Scholarship before = scholarships.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            boolean locked = awards.withApprovedAwards(institution, Set.of(id)).contains(id);
            Scholarship after = before.edit(version, draft.name(), benefit, Benefit.cap(draft.periodCap()), draft.conceptIds(),
                    locked, clock.instant());
            requireCoverable(institution, after.concepts(), before.concepts());
            scholarships.replace(institution, after, version);
            auditLogWriter.append(ScholarshipsAudit.scholarshipUpdated(actor, auditRequestId, before, after));
            return view(institution, after, locked);
        });
    }

    /** Deactivates the scholarship: it takes no new award and stops applying to the charges issued afterwards. */
    public ScholarshipView deactivate(AuthenticatedActor actor, String requestId, UUID id) {
        return setActive(actor, requestId, id, false);
    }

    /** Reactivates the scholarship. */
    public ScholarshipView reactivate(AuthenticatedActor actor, String requestId, UUID id) {
        return setActive(actor, requestId, id, true);
    }

    private ScholarshipView setActive(AuthenticatedActor actor, String requestId, UUID id, boolean active) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Scholarship before = scholarships.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            Scholarship after = before.withActive(active, clock.instant());
            if (after != before) {
                scholarships.replace(institution, after, before.version());
                auditLogWriter.append(ScholarshipsAudit.scholarshipActivation(actor, auditRequestId, before, after));
            }
            return view(institution, after, awards.withApprovedAwards(institution, Set.of(id)).contains(id));
        });
    }

    public Page<ScholarshipView> list(AuthenticatedActor actor, String requestId, ScholarshipFilter filter, PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.benefitType() != null && !isBenefitType(filter.benefitType())) {
            throw new InvalidScholarshipsValueException();
        }
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Page<Scholarship> found = scholarships.list(institution, filter, page);
            Set<UUID> ids = new HashSet<>();
            found.items().forEach(scholarship -> ids.add(scholarship.id()));
            Set<UUID> locked = ids.isEmpty() ? Set.of() : awards.withApprovedAwards(institution, ids);
            Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, found.items());
            return found.map(scholarship -> ScholarshipView.of(scholarship, conceptViews(scholarship, labels),
                    locked.contains(scholarship.id())));
        });
    }

    public ScholarshipView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Scholarship found = scholarships.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            return view(institution, found, awards.withApprovedAwards(institution, Set.of(id)).contains(id));
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

    private ScholarshipView view(InstitutionId institution, Scholarship scholarship, boolean benefitLocked) {
        Map<UUID, PaymentConceptLabel> labels = labelsOf(institution, List.of(scholarship));
        return ScholarshipView.of(scholarship, conceptViews(scholarship, labels), benefitLocked);
    }

    private Map<UUID, PaymentConceptLabel> labelsOf(InstitutionId institution, Collection<Scholarship> found) {
        Set<UUID> ids = new HashSet<>();
        found.forEach(scholarship -> ids.addAll(scholarship.concepts()));
        return ids.isEmpty() ? Map.of() : concepts.labels(institution, ids);
    }

    private static List<ScholarshipView.ConceptView> conceptViews(Scholarship scholarship, Map<UUID, PaymentConceptLabel> labels) {
        return scholarship.concepts().stream().map(id -> labels.get(id))
                .filter(Objects::nonNull)
                .map(label -> new ScholarshipView.ConceptView(label.id(), label.code(), label.name())).toList();
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
