package com.confia.catalog.application;

import com.confia.catalog.domain.InactiveReferenceException;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.PriceList;
import com.confia.catalog.domain.PriceListItem;
import com.confia.catalog.domain.PriceListScope;
import com.confia.catalog.domain.PriceListStatus;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits, lists and reads the price lists of the institution of the actor, and adds prices to them
 * (docs/cortes/catalog-price-lists.md). The institution is the one of the actor, never a member of a request. Nothing is
 * deleted: a list is archived and a price is replaced by a new validity.
 *
 * <p>Adding a price locks the row of the list ({@code SELECT ... FOR UPDATE}), so two requests for the same list take
 * turns, and then closes the open validity the new price replaces and inserts the new one in the same transaction
 * (INV-11); the database refuses any overlap that slips through. Editing a list asks for the version the editor saw.
 */
public final class ManagePriceLists {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "createdAt");

    private final TransactionRunner transactionRunner;
    private final PriceListRepository lists;
    private final PriceListItemRepository items;
    private final PaymentConceptRepository concepts;
    private final AcademicStructureDirectory structure;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManagePriceLists(TransactionRunner transactionRunner, PriceListRepository lists,
            PriceListItemRepository items, PaymentConceptRepository concepts, AcademicStructureDirectory structure,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.lists = Objects.requireNonNull(lists, "lists");
        this.items = Objects.requireNonNull(items, "items");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.structure = Objects.requireNonNull(structure, "structure");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PriceListView create(AuthenticatedActor actor, String requestId, UUID academicYearId, String name,
            String scope, UUID scopeRefId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PriceList list = PriceList.create(UUID.randomUUID(), academicYearId, name, PriceListScope.parse(scope),
                    scopeRefId, clock.instant());
            requireReferencesInInstitution(institution, list);
            lists.insert(institution, list);
            auditLogWriter.append(CatalogAudit.priceListCreated(actor, auditRequestId, list.id(),
                    CatalogAudit.json(list)));
            return PriceListView.of(list);
        });
    }

    public PriceListView update(AuthenticatedActor actor, String requestId, UUID id, long version, String name,
            String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PriceList before = lists.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            PriceList after = before.edit(version, name, PriceListStatus.parse(status), clock.instant());
            lists.update(institution, after, version);
            auditLogWriter.append(CatalogAudit.priceListUpdated(actor, auditRequestId, id,
                    CatalogAudit.json(before), CatalogAudit.json(after)));
            return PriceListView.of(after);
        });
    }

    public Page<PriceListView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            UUID academicYearId, String scope, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "name", true);
        PriceListFilter filter = new PriceListFilter(academicYearId,
                scope == null || scope.isBlank() ? null : PriceListScope.parse(scope),
                status == null || status.isBlank() ? null : PriceListStatus.parse(status));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> lists.list(institution, request, filter).map(PriceListView::of));
    }

    /** The list with every validity of every price it holds. */
    public PriceListDetailView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            PriceList list = lists.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            List<PriceListItemView> prices = items.findByList(institution, id).stream()
                    .map(PriceListItemView::of).toList();
            return new PriceListDetailView(PriceListView.of(list), prices);
        });
    }

    /**
     * Adds a price (a new validity) of a concept to a list. When the concept already has an open validity that the new
     * price replaces, that one is closed the day before the new one starts, in the same transaction.
     *
     * @param validTo the last day of the validity, or {@code null} for no end
     */
    public PriceListItemView addPrice(AuthenticatedActor actor, String requestId, UUID priceListId,
            UUID paymentConceptId, String amount, String currency, LocalDate validFrom, LocalDate validTo) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            PriceList list = lists.lockById(institution, priceListId).orElseThrow(ResourceNotFoundException::new);
            list.requireAcceptsPrices();
            // Lock order: the list, then the concept, so a concept cannot be deactivated while its price is being added.
            PaymentConcept concept = concepts.lockShareById(institution, paymentConceptId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!concept.active()) {
                throw new InactiveReferenceException();
            }
            PriceListItem price = PriceListItem.create(UUID.randomUUID(), priceListId, paymentConceptId, amount,
                    currency, validFrom, validTo, actor.accountId(), clock.instant());

            Optional<PriceListItem> open = items.findOpen(institution, priceListId, paymentConceptId);
            Optional<LocalDate> lastDay = open.isPresent() ? open.get().closingDateFor(price) : Optional.empty();
            if (lastDay.isPresent()) {
                items.closeValidity(institution, open.get().id(), lastDay.get());
            }
            items.insert(institution, price);
            auditLogWriter.append(CatalogAudit.priceListItemCreated(actor, auditRequestId, price.id(),
                    CatalogAudit.json(price, lastDay.isPresent() ? open.get().id() : null, lastDay.orElse(null))));
            return PriceListItemView.of(price);
        });
    }

    private void requireReferencesInInstitution(InstitutionId institution, PriceList list) {
        boolean found = structure.academicYearExists(institution, list.academicYearId());
        if (found && list.scope() == PriceListScope.MODALITY) {
            found = structure.modalityExists(institution, list.scopeRefId());
        } else if (found && list.scope() == PriceListScope.GRADE) {
            found = structure.gradeExists(institution, list.scopeRefId());
        }
        if (!found) {
            throw new ResourceNotFoundException();
        }
    }
}
