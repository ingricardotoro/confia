package com.confia.catalog.application;

import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.TaxRate;
import com.confia.catalog.domain.TaxRatePeriod;
import com.confia.kernel.InstitutionId;
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
 * Creates, edits, lists and reads the tax rates of the institution of the actor, and adds periods (percentages with a
 * validity) to them (docs/cortes/catalog-tax-rates.md). The institution is the one of the actor, never a member of a
 * request. Nothing is deleted: a rate is deactivated and a period is replaced by a new one. No percentage is known to this
 * class: every figure comes from the administrator.
 *
 * <p>Adding a period locks the row of the rate ({@code SELECT ... FOR UPDATE}), so two requests for the same rate take
 * turns, and then closes the open period the new one replaces and inserts the new one in the same transaction (INV-11);
 * the database refuses any overlap that slips through. Editing a rate asks for the version the editor saw.
 */
public final class ManageTaxRates {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name");

    private final TransactionRunner transactionRunner;
    private final TaxRateRepository rates;
    private final TaxRatePeriodRepository periods;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageTaxRates(TransactionRunner transactionRunner, TaxRateRepository rates,
            TaxRatePeriodRepository periods, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.periods = Objects.requireNonNull(periods, "periods");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TaxRateView create(AuthenticatedActor actor, String requestId, String code, String name) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            TaxRate rate = TaxRate.create(UUID.randomUUID(), code, name, clock.instant());
            rates.insert(institution, rate);
            auditLogWriter.append(CatalogAudit.taxRateCreated(actor, auditRequestId, rate.id(),
                    CatalogAudit.json(rate)));
            return TaxRateView.of(rate);
        });
    }

    public TaxRateView update(AuthenticatedActor actor, String requestId, UUID id, long version, String name,
            boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            TaxRate before = rates.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            TaxRate after = before.edit(version, name, active, clock.instant());
            rates.update(institution, after, version);
            auditLogWriter.append(CatalogAudit.taxRateUpdated(actor, auditRequestId, id, CatalogAudit.json(before),
                    CatalogAudit.json(after)));
            return TaxRateView.of(after);
        });
    }

    public Page<TaxRateView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            Boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "code", true);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> rates.list(institution, request, new TaxRateFilter(active)).map(TaxRateView::of));
    }

    /** The rate with every period it has had, oldest first. */
    public TaxRateDetailView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            TaxRate rate = rates.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            List<TaxRatePeriodView> history = periods.findByTaxRate(institution, id).stream()
                    .map(TaxRatePeriodView::of).toList();
            return new TaxRateDetailView(TaxRateView.of(rate), history);
        });
    }

    /**
     * Adds a period (a percentage with a validity) to a rate. When the rate already has an open period that the new one
     * replaces, that one is closed the day before the new one starts, in the same transaction.
     *
     * @param ratePercent the percentage as the text of the API
     * @param validTo the last day of the validity, or {@code null} for no end
     */
    public TaxRatePeriodView addPeriod(AuthenticatedActor actor, String requestId, UUID taxRateId,
            String ratePercent, LocalDate validFrom, LocalDate validTo) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CatalogAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            TaxRate rate = rates.lockById(institution, taxRateId).orElseThrow(ResourceNotFoundException::new);
            rate.requireActive();
            TaxRatePeriod period = TaxRatePeriod.create(UUID.randomUUID(), taxRateId, ratePercent, validFrom, validTo,
                    actor.accountId(), clock.instant());

            Optional<TaxRatePeriod> open = periods.findOpen(institution, taxRateId);
            Optional<LocalDate> lastDay = open.isPresent() ? open.get().closingDateFor(period) : Optional.empty();
            if (lastDay.isPresent()) {
                periods.closeValidity(institution, open.get().id(), lastDay.get());
            }
            periods.insert(institution, period);
            auditLogWriter.append(CatalogAudit.taxRatePeriodCreated(actor, auditRequestId, period.id(),
                    CatalogAudit.json(period, lastDay.isPresent() ? open.get().id() : null, lastDay.orElse(null))));
            return TaxRatePeriodView.of(period);
        });
    }
}
