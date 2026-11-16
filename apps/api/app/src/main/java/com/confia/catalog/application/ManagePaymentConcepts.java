package com.confia.catalog.application;

import com.confia.catalog.domain.PaymentCategory;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and lists the payment concepts of the institution of the actor. Nothing is deleted: a concept is
 * deactivated with {@link #update}. The code is unique in the institution whatever its capitals, which the database
 * enforces, and it is never changed after creation.
 */
public final class ManagePaymentConcepts {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name");

    private final TransactionRunner transactionRunner;
    private final PaymentConceptRepository concepts;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManagePaymentConcepts(TransactionRunner transactionRunner, PaymentConceptRepository concepts,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PaymentConceptView create(AuthenticatedActor actor, String requestId, String code, String name,
            String category, boolean taxable, boolean recurring) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PaymentConcept concept = PaymentConcept.create(UUID.randomUUID(), code, name,
                    PaymentCategory.parse(category), taxable, recurring, clock.instant());
            concepts.insert(institution, concept);
            auditLogWriter.append(CatalogAudit.created(actor, auditRequestId, concept.id(),
                    CatalogAudit.json(concept)));
            return PaymentConceptView.of(concept);
        });
    }

    public PaymentConceptView update(AuthenticatedActor actor, String requestId, UUID id, long version,
            String name, String category, boolean taxable, boolean recurring, boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PaymentConcept before = concepts.findById(institution, id)
                    .orElseThrow(ResourceNotFoundException::new);
            PaymentConcept after = before.edit(version, name, PaymentCategory.parse(category), taxable, recurring,
                    active, clock.instant());
            concepts.update(institution, after, version);
            auditLogWriter.append(CatalogAudit.updated(actor, auditRequestId, id, CatalogAudit.json(before),
                    CatalogAudit.json(after)));
            return PaymentConceptView.of(after);
        });
    }

    public Page<PaymentConceptView> list(AuthenticatedActor actor, String requestId, int page, int size,
            String sort, String category, Boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "code", true);
        PaymentConceptFilter filter = new PaymentConceptFilter(
                category == null || category.isBlank() ? null : PaymentCategory.parse(category), active);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> concepts.list(institution, request, filter).map(PaymentConceptView::of));
    }
}
