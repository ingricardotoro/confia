package com.confia.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.domain.DuplicateTaxRateCodeException;
import com.confia.catalog.domain.InactiveReferenceException;
import com.confia.catalog.domain.InvalidCatalogValueException;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.catalog.domain.TaxRate;
import com.confia.catalog.domain.TaxRatePeriodOverlapException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The use case of the tax rates over in-memory ports: what is written, in which transaction and with which audit entry. The
 * percentages are inputs of the cases, never values the system knows.
 */
class ManageTaxRatesTest {

    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW,
            NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final InMemoryTaxRates rates = new InMemoryTaxRates();
    private final InMemoryTaxRatePeriods periods = new InMemoryTaxRatePeriods();
    private final ManageTaxRates service = new ManageTaxRates(runner, rates, periods, auditWriter, clock);

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private TaxRateView isv() {
        return service.create(actor, requestId, "ISV", "Impuesto sobre ventas");
    }

    private TaxRatePeriodView add(UUID rate, String percent, String from, String to) {
        return service.addPeriod(actor, requestId, rate, percent, d(from), to == null ? null : d(to));
    }

    // --- create ---

    @Test
    void creatingARateStoresItWithNoPercentageAndWritesOneCreatedEntryInTheSameTransaction() {
        TaxRateView view = isv();

        assertThat(view.code()).isEqualTo("ISV");
        assertThat(view.active()).isTrue();
        assertThat(view.version()).isZero();
        assertThat(periods.all()).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("catalog.tax_rate.created");
            assertThat(entry.entityType()).isEqualTo("catalog.tax_rate");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo(
                    "{\"code\":\"ISV\",\"name\":\"Impuesto sobre ventas\",\"isActive\":true,\"version\":0}");
            assertThat(entry.actorId()).isEqualTo(actor.accountId());
        });
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aCodeAlreadyTakenWithAnotherCapitalizationIsADuplicateAndLeavesNoEntryButAnotherInstitutionMayUseIt() {
        isv();
        audit.clear();

        assertThatThrownBy(() -> service.create(actor, requestId, "isv", "Otra")).isInstanceOf(
                DuplicateTaxRateCodeException.class);
        assertThat(audit).isEmpty();
        assertThat(rates.rows).hasSize(1);
        assertThat(transactionCalls).contains("rollback");

        rates.arrange(new InstitutionId(UUID.randomUUID()), "OTRA", "Otra institucion", true);
        assertThat(service.create(actor, requestId, "OTRA", "Mia").code()).isEqualTo("OTRA");
    }

    @Test
    void aBadCodeOrNameIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.create(actor, requestId, " ", "Nombre"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, "ISV", "N".repeat(121)))
                .isInstanceOf(InvalidCatalogValueException.class);

        assertThat(audit).isEmpty();
        assertThat(rates.rows).isEmpty();
    }

    // --- update ---

    @Test
    void anEditChangesNameAndFlagAdvancesTheVersionAndAuditsBeforeAndAfter() {
        TaxRateView created = isv();
        audit.clear();

        TaxRateView off = service.update(actor, requestId, created.id(), 0, "Otro nombre", false);
        TaxRateView on = service.update(actor, requestId, created.id(), 1, "Otro nombre", true);

        assertThat(off.code()).isEqualTo("ISV");
        assertThat(off.version()).isEqualTo(1);
        assertThat(off.active()).isFalse();
        assertThat(on.version()).isEqualTo(2);
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).action()).isEqualTo("catalog.tax_rate.updated");
        assertThat(audit.get(0).beforeValue()).contains("\"name\":\"Impuesto sobre ventas\"")
                .contains("\"isActive\":true");
        assertThat(audit.get(0).afterValue()).contains("\"name\":\"Otro nombre\"").contains("\"isActive\":false")
                .contains("\"version\":1");
        assertThat(rates.forUpdateLocks).as("the row is locked for the edit").isEqualTo(2);
    }

    @Test
    void aStaleVersionIsRefusedAndAnUnknownOrForeignRateIsNotFound() {
        TaxRateView created = isv();
        TaxRate foreign = rates.arrange(new InstitutionId(UUID.randomUUID()), "AJENA", "Ajena", true);
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 3, "X", true))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, UUID.randomUUID(), 0, "X", true))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, foreign.id(), 0, "X", true))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(audit).isEmpty();
        assertThat(rates.rows.values()).filteredOn(rate -> rate.id().equals(created.id())).singleElement()
                .satisfies(rate -> assertThat(rate.version()).isZero());
    }

    // --- periods (INV-11) ---

    @Test
    void aSecondPeriodClosesTheOpenOneTheDayBeforeAndBothAreAuditedWithTheClosedPrevious() {
        TaxRateView created = isv();
        audit.clear();

        TaxRatePeriodView first = add(created.id(), "12.5", "2027-01-01", null);
        TaxRatePeriodView second = add(created.id(), "13.25", "2027-07-01", null);

        assertThat(first.ratePercent()).isEqualTo("12.5000");
        assertThat(second.ratePercent()).isEqualTo("13.2500");
        assertThat(periods.findByTaxRate(institution, created.id())).satisfiesExactly(
                closed -> {
                    assertThat(closed.id()).isEqualTo(first.id());
                    assertThat(closed.validTo()).isEqualTo(d("2027-06-30"));
                },
                open -> {
                    assertThat(open.id()).isEqualTo(second.id());
                    assertThat(open.validTo()).isNull();
                });
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).action()).isEqualTo("catalog.tax_rate_period.created");
        assertThat(audit.get(0).entityType()).isEqualTo("catalog.tax_rate_period");
        assertThat(audit.get(0).afterValue()).contains("\"ratePercent\":\"12.5000\"").doesNotContain("closedPrevious");
        assertThat(audit.get(1).entityId()).isEqualTo(second.id().toString());
        assertThat(audit.get(1).afterValue()).contains("\"ratePercent\":\"13.2500\"")
                .contains("\"closedPreviousPeriodId\":\"" + first.id() + "\"")
                .contains("\"closedPreviousValidTo\":\"2027-06-30\"");
        assertThat(rates.forUpdateLocks).as("the row of the rate is locked for each addition").isEqualTo(2);
    }

    @Test
    void anOverlapIsRefusedAndTheOpenPeriodStaysOpenAndNothingIsAudited() {
        TaxRateView created = isv();
        add(created.id(), "12.5", "2027-01-01", null);
        audit.clear();

        assertThatThrownBy(() -> add(created.id(), "13.25", "2027-01-01", null))
                .isInstanceOf(TaxRatePeriodOverlapException.class);
        assertThatThrownBy(() -> add(created.id(), "13.25", "2027-03-01", "2027-03-31"))
                .isInstanceOf(TaxRatePeriodOverlapException.class);

        assertThat(periods.all()).singleElement().satisfies(period -> assertThat(period.validTo()).isNull());
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void aBoundedPeriodBeforeTheOpenOneIsAddedWithoutClosingAnything() {
        TaxRateView created = isv();
        add(created.id(), "12.5", "2027-01-01", null);

        add(created.id(), "10", "2026-01-01", "2026-12-31");

        assertThat(periods.all()).hasSize(2).filteredOn(period -> period.validTo() == null).hasSize(1);
    }

    @Test
    void anInactiveRateTakesNoPeriodAndAnUnknownOrForeignOneIsNotFound() {
        TaxRateView created = isv();
        service.update(actor, requestId, created.id(), 0, "Impuesto sobre ventas", false);
        TaxRate foreign = rates.arrange(new InstitutionId(UUID.randomUUID()), "AJENA", "Ajena", true);
        audit.clear();

        assertThatThrownBy(() -> add(created.id(), "12.5", "2027-01-01", null))
                .isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> add(UUID.randomUUID(), "12.5", "2027-01-01", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> add(foreign.id(), "12.5", "2027-01-01", null))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(periods.all()).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aBadPercentageOrDateIsRefusedAndLeavesNoPeriod() {
        TaxRateView created = isv();
        audit.clear();

        for (String bad : new String[] {"0", "-5", "100.0001", "5.00001", "abc"}) {
            assertThatThrownBy(() -> add(created.id(), bad, "2027-01-01", null)).as(bad)
                    .isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> add(created.id(), "5", "2027-02-01", "2027-01-31"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> add(created.id(), "5", "1999-12-31", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> add(created.id(), "5", "2027-01-01", "2101-01-01"))
                .isInstanceOf(InvalidCatalogValueException.class);

        assertThat(periods.all()).isEmpty();
        assertThat(audit).isEmpty();
    }

    // --- reads ---

    @Test
    void theDetailShowsEveryPeriodOldestFirstAndAnUnknownRateIsNotFound() {
        TaxRateView created = isv();
        add(created.id(), "13.25", "2027-07-01", null);
        add(created.id(), "12.5", "2027-01-01", "2027-06-30");

        TaxRateDetailView detail = service.get(actor, requestId, created.id());

        assertThat(detail.rate().code()).isEqualTo("ISV");
        assertThat(detail.periods()).extracting(TaxRatePeriodView::validFrom)
                .containsExactly(d("2027-01-01"), d("2027-07-01"));
        assertThatThrownBy(() -> service.get(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theListIsScopedToTheInstitutionSortedByCodeByDefaultAndFilteredByActive() {
        rates.arrange(institution, "b-rate", "Beta", true);
        rates.arrange(institution, "A-rate", "Zeta", true);
        rates.arrange(institution, "C-rate", "Alfa", false);
        rates.arrange(new InstitutionId(UUID.randomUUID()), "OTHER", "Otra", true);

        assertThat(service.list(actor, requestId, 0, 50, null, null).items()).extracting(TaxRateView::code)
                .containsExactly("A-rate", "b-rate", "C-rate");
        assertThat(service.list(actor, requestId, 0, 50, "name,desc", null).items())
                .extracting(TaxRateView::name).containsExactly("Zeta", "Beta", "Alfa");
        assertThat(service.list(actor, requestId, 0, 50, null, false).items()).extracting(TaxRateView::code)
                .containsExactly("C-rate");
        assertThat(service.list(actor, requestId, 0, 2, null, null).total()).isEqualTo(3);
        assertThat(audit).isEmpty();
    }

    @Test
    void theListRefusesAFieldOrSizeOutsideItsSet() {
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, "isActive", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 201, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, -1, 50, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
    }
}
