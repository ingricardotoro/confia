package com.confia.catalog.application;

import com.confia.catalog.domain.InactiveReferenceException;
import com.confia.catalog.domain.PaymentCategory;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.TaxRate;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Creates, edits and lists the payment concepts of the institution of the actor. Nothing is deleted: a concept is
 * deactivated with {@link #update}. The code is unique in the institution whatever its capitals, which the database
 * enforces, and it is never changed after creation.
 *
 * <p>A taxable concept names its tax rate (cut 3) and a concept that is not taxable names none. The rate must exist in the
 * institution (404 otherwise) and be active (409 {@code inactive-reference}); it is read in share mode in the same
 * transaction as the write, so nobody deactivates it in between. An edit that keeps the rate the concept already has does
 * not ask for it to be active, so a concept can still be renamed or deactivated after its rate was deactivated; the
 * exception is a concept that goes from inactive to active, which needs an active rate.
 */
public final class ManagePaymentConcepts {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name");

    private final TransactionRunner transactionRunner;
    private final PaymentConceptRepository concepts;
    private final TaxRateRepository taxRates;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManagePaymentConcepts(TransactionRunner transactionRunner, PaymentConceptRepository concepts,
            TaxRateRepository taxRates, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.taxRates = Objects.requireNonNull(taxRates, "taxRates");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PaymentConceptView create(AuthenticatedActor actor, String requestId, String code, String name,
            String category, boolean taxable, boolean recurring, UUID taxRateId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PaymentConcept concept = PaymentConcept.create(UUID.randomUUID(), code, name,
                    PaymentCategory.parse(category), taxable, recurring, taxRateId, clock.instant());
            TaxRate rate = assignableRate(institution, concept, null);
            concepts.insert(institution, concept);
            auditLogWriter.append(CatalogAudit.created(actor, auditRequestId, concept.id(),
                    CatalogAudit.json(concept)));
            return PaymentConceptView.of(concept, rate);
        });
    }

    public PaymentConceptView update(AuthenticatedActor actor, String requestId, UUID id, long version,
            String name, String category, boolean taxable, boolean recurring, boolean active,
            UUID taxRateId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PaymentConcept before = concepts.findById(institution, id)
                    .orElseThrow(ResourceNotFoundException::new);
            PaymentConcept after = before.edit(version, name, PaymentCategory.parse(category), taxable, recurring,
                    active, taxRateId, clock.instant());
            TaxRate rate = assignableRate(institution, after, before);
            concepts.update(institution, after, version);
            auditLogWriter.append(CatalogAudit.updated(actor, auditRequestId, id, CatalogAudit.json(before),
                    CatalogAudit.json(after)));
            return PaymentConceptView.of(after, rate);
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
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Page<PaymentConcept> found = concepts.list(institution, request, filter);
            Set<UUID> rateIds = found.items().stream().map(PaymentConcept::taxRateId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<UUID, TaxRate> rates = rateIds.isEmpty() ? Map.of() : taxRates.findByIds(institution, rateIds);
            return found.map(concept -> PaymentConceptView.of(concept,
                    concept.taxRateId() == null ? null : rates.get(concept.taxRateId())));
        });
    }

    /**
     * The tax rate the concept is about to be written with, read in share mode: it must exist in the institution and,
     * unless the concept already had it and is not being reactivated, be active. {@code null} when the concept has none.
     */
    private TaxRate assignableRate(InstitutionId institution, PaymentConcept concept, PaymentConcept before) {
        if (concept.taxRateId() == null) {
            return null;
        }
        TaxRate rate = taxRates.lockShareById(institution, concept.taxRateId())
                .orElseThrow(ResourceNotFoundException::new);
        if (!rate.active()) {
            boolean keptAsIs = before != null && rate.id().equals(before.taxRateId());
            boolean reactivated = before != null && !before.active() && concept.active();
            if (!keptAsIs || reactivated) {
                throw new InactiveReferenceException();
            }
        }
        return rate;
    }
}
