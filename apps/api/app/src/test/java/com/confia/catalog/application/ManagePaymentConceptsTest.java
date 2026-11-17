package com.confia.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.domain.DuplicateCodeException;
import com.confia.catalog.domain.InactiveReferenceException;
import com.confia.catalog.domain.InvalidCatalogValueException;
import com.confia.catalog.domain.PaymentCategory;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.catalog.domain.TaxRate;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagePaymentConceptsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(86_400);

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
    private final InMemoryConcepts store = new InMemoryConcepts();
    private final InMemoryTaxRates rates = new InMemoryTaxRates();
    private final ManagePaymentConcepts concepts = new ManagePaymentConcepts(runner, store, rates, auditWriter, clock);

    private PaymentConcept arranged(InstitutionId owner, String code, String name, PaymentCategory category,
            boolean active) {
        PaymentConcept concept = new PaymentConcept(UUID.randomUUID(), code, name, category, true, true, active, null,
                0, EARLIER, EARLIER);
        store.rows.put(new Key(owner, concept.id()), concept);
        return concept;
    }

    // --- create ---

    @Test
    void creatingAConceptStoresItAndWritesOneCreatedEntryInTheSameTransaction() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);

        PaymentConceptView view = concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true,
                isv.id());

        assertThat(view.active()).isTrue();
        assertThat(view.taxRateId()).isEqualTo(isv.id());
        assertThat(view.taxRateCode()).isEqualTo("ISV");
        assertThat(view.taxRateName()).isEqualTo("Impuesto sobre ventas");
        assertThat(rates.forShareLocks).as("the rate is read in share mode in the same transaction").isEqualTo(1);
        assertThat(view.code()).isEqualTo("COLEG");
        assertThat(view.category()).isEqualTo("TUITION");
        assertThat(view.taxable()).isTrue();
        assertThat(view.recurring()).isTrue();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("catalog.payment_concept.created");
            assertThat(entry.entityType()).isEqualTo("catalog.payment_concept");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"code\":\"COLEG\",\"name\":\"Colegiatura\","
                    + "\"category\":\"TUITION\",\"isTaxable\":true,\"isRecurring\":true,\"isActive\":true,"
                    + "\"taxRateId\":\"" + isv.id() + "\"}");
        });
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aCodeAlreadyTakenWithAnotherCapitalizationIsADuplicateAndLeavesNoEntry() {
        concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", false, true, null);
        audit.clear();

        assertThatThrownBy(() -> concepts.create(actor, requestId, "coleg", "Otro", "OTHER", false, false, null))
                .isInstanceOf(DuplicateCodeException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows).hasSize(1);
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void theSameCodeInAnotherInstitutionIsNotADuplicate() {
        arranged(new InstitutionId(UUID.randomUUID()), "COLEG", "Otra", PaymentCategory.TUITION, true);

        assertThat(concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", false, true, null).code())
                .isEqualTo("COLEG");
    }

    @Test
    void aBlankCodeAnUnknownCategoryAndATooLongNameAreRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> concepts.create(actor, requestId, " ", "Nombre", "OTHER", false, false, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "C".repeat(31), "Nombre", "OTHER", false, false,
                null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "COD", "Nombre", "SCHOLARSHIP", false, false, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "COD", "N".repeat(121), "OTHER", false, false, null))
                .isInstanceOf(InvalidCatalogValueException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    // --- update ---

    @Test
    void anEditKeepsTheCodeWritesBeforeAndAfterAndDeactivatesAndReactivates() {
        PaymentConcept concept = arranged(institution, "COLEG", "Colegiatura", PaymentCategory.TUITION, true);

        PaymentConceptView off = concepts.update(actor, requestId, concept.id(), 0, "Mensualidad", "OTHER", false,
                false, false, null);
        PaymentConceptView on = concepts.update(actor, requestId, concept.id(), 1, "Mensualidad", "OTHER", false,
                false, true, null);

        assertThat(off.code()).isEqualTo("COLEG");
        assertThat(off.version()).isEqualTo(1);
        assertThat(on.version()).isEqualTo(2);
        assertThat(off.active()).isFalse();
        assertThat(off.updatedAt()).isEqualTo(NOW);
        assertThat(off.createdAt()).isEqualTo(EARLIER);
        assertThat(on.active()).isTrue();
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0).action()).isEqualTo("catalog.payment_concept.updated");
        assertThat(audit.get(0).beforeValue()).contains("\"name\":\"Colegiatura\"").contains("\"isActive\":true");
        assertThat(audit.get(0).afterValue()).contains("\"name\":\"Mensualidad\"").contains("\"isActive\":false")
                .contains("\"category\":\"OTHER\"").contains("\"isTaxable\":false");
        assertThat(audit.get(1).beforeValue()).contains("\"isActive\":false");
    }

    @Test
    void anEditWithAStaleVersionIsRefusedAndLeavesTheRowAndTheAuditUntouched() {
        PaymentConcept concept = arranged(institution, "COLEG", "Colegiatura", PaymentCategory.TUITION, true);
        concepts.update(actor, requestId, concept.id(), 0, "Primero", "OTHER", false, false, true, null);
        audit.clear();

        assertThatThrownBy(() -> concepts.update(actor, requestId, concept.id(), 0, "Segundo", "OTHER", false,
                false, true, null)).isInstanceOf(StaleVersionException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows.values()).singleElement().satisfies(stored -> {
            assertThat(stored.name()).isEqualTo("Primero");
            assertThat(stored.version()).isEqualTo(1);
        });
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void anUnknownConceptOrOneOfAnotherInstitutionIsNotFoundAndLeavesNoEntry() {
        PaymentConcept foreign = arranged(new InstitutionId(UUID.randomUUID()), "COLEG", "Otra",
                PaymentCategory.TUITION, true);

        assertThatThrownBy(() -> concepts.update(actor, requestId, UUID.randomUUID(), 0, "X", "OTHER", false, false,
                true, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> concepts.update(actor, requestId, foreign.id(), 0, "X", "OTHER", false, false, true,
                null))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows.values()).containsExactly(foreign);
    }

    @Test
    void anEditWithAnUnknownCategoryIsRefused() {
        PaymentConcept concept = arranged(institution, "COLEG", "Colegiatura", PaymentCategory.TUITION, true);

        assertThatThrownBy(() -> concepts.update(actor, requestId, concept.id(), 0, "X", "nope", false, false, true,
                null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThat(audit).isEmpty();
    }

    // --- the tax rate of a concept (cut 3) ---

    @Test
    void aTaxableConceptWithoutATaxRateAndAConceptThatIsNotTaxableWithOneAreRefusedAndLeaveNothing() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);

        assertThatThrownBy(() -> concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "BUS", "Transporte", "TRANSPORT", false, true,
                isv.id())).isInstanceOf(InvalidCatalogValueException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void aTaxRateOfAnotherInstitutionOrUnknownIsNotFoundAndAnInactiveOneIsAnInactiveReference() {
        TaxRate foreign = rates.arrange(new InstitutionId(UUID.randomUUID()), "ISV", "Ajena", true);
        TaxRate inactive = rates.arrange(institution, "OLD", "Vieja", false);

        assertThatThrownBy(() -> concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true,
                foreign.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true,
                UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true,
                inactive.id())).isInstanceOf(InactiveReferenceException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void anEditAssignsChangesAndClearsTheTaxRateAndAuditsItBeforeAndAfter() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);
        TaxRate other = rates.arrange(institution, "TUR", "Turismo", true);
        PaymentConcept concept = arranged(institution, "COLEG", "Colegiatura", PaymentCategory.TUITION, true);

        PaymentConceptView assigned = concepts.update(actor, requestId, concept.id(), 0, "Colegiatura", "TUITION",
                true, true, true, isv.id());
        PaymentConceptView changed = concepts.update(actor, requestId, concept.id(), 1, "Colegiatura", "TUITION",
                true, true, true, other.id());
        PaymentConceptView cleared = concepts.update(actor, requestId, concept.id(), 2, "Colegiatura", "TUITION",
                false, true, true, null);

        assertThat(assigned.taxRateId()).isEqualTo(isv.id());
        assertThat(changed.taxRateCode()).isEqualTo("TUR");
        assertThat(cleared.taxRateId()).isNull();
        assertThat(cleared.taxRateCode()).isNull();
        assertThat(audit.get(0).beforeValue()).doesNotContain("taxRateId");
        assertThat(audit.get(0).afterValue()).contains("\"taxRateId\":\"" + isv.id() + "\"");
        assertThat(audit.get(1).beforeValue()).contains("\"taxRateId\":\"" + isv.id() + "\"");
        assertThat(audit.get(1).afterValue()).contains("\"taxRateId\":\"" + other.id() + "\"");
        assertThat(audit.get(2).afterValue()).doesNotContain("taxRateId");
    }

    @Test
    void aLegacyTaxableConceptWithoutARateMayBeEditedAndToggledButNotMadeTaxableAgainWithoutOne() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);
        PaymentConcept legacy = arranged(institution, "COLEG", "Colegiatura", PaymentCategory.TUITION, true);

        PaymentConceptView renamed = concepts.update(actor, requestId, legacy.id(), 0, "Nueva", "TUITION", true, true,
                true, null);
        PaymentConceptView off = concepts.update(actor, requestId, legacy.id(), 1, "Nueva", "TUITION", true, true,
                false, null);
        PaymentConceptView on = concepts.update(actor, requestId, legacy.id(), 2, "Nueva", "TUITION", true, true,
                true, null);
        concepts.update(actor, requestId, legacy.id(), 3, "Nueva", "TUITION", false, true, true, null);

        assertThat(renamed.taxRateId()).isNull();
        assertThat(off.active()).isFalse();
        assertThat(on.active()).isTrue();
        assertThat(audit).hasSize(4);
        assertThatThrownBy(() -> concepts.update(actor, requestId, legacy.id(), 4, "Nueva", "TUITION", true, true,
                true, null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.update(actor, requestId, legacy.id(), 4, "Nueva", "TUITION", false, true,
                true, isv.id())).isInstanceOf(InvalidCatalogValueException.class);
        assertThat(audit).hasSize(4);
    }

    @Test
    void anInactiveRateCannotBeAssignedButAConceptThatAlreadyHasItCanStillBeEditedAndDeactivated() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);
        PaymentConceptView created = concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true,
                isv.id());
        rates.rows.put(new InMemoryTaxRates.Key(institution, isv.id()), isv.edit(0, isv.name(), false, NOW));
        TaxRate other = rates.arrange(institution, "TUR", "Turismo", false);

        assertThatThrownBy(() -> concepts.update(actor, requestId, created.id(), 0, "Colegiatura", "TUITION", true,
                true, true, other.id())).isInstanceOf(InactiveReferenceException.class);
        PaymentConceptView off = concepts.update(actor, requestId, created.id(), 0, "Colegiatura", "TUITION", true,
                true, false, isv.id());

        assertThat(off.active()).isFalse();
        assertThat(off.taxRateId()).isEqualTo(isv.id());
        assertThatThrownBy(() -> concepts.update(actor, requestId, created.id(), 1, "Colegiatura", "TUITION", true,
                true, true, isv.id())).as("inactive to active needs an active rate")
                .isInstanceOf(InactiveReferenceException.class);
        assertThat(concepts.update(actor, requestId, created.id(), 1, "Colegiatura", "TUITION", true, true, false,
                isv.id()).active()).isFalse();
    }

    @Test
    void theListShowsTheCodeAndTheNameOfTheTaxRateOfEachConcept() {
        TaxRate isv = rates.arrange(institution, "ISV", "Impuesto sobre ventas", true);
        concepts.create(actor, requestId, "COLEG", "Colegiatura", "TUITION", true, true, isv.id());
        concepts.create(actor, requestId, "BUS", "Transporte", "TRANSPORT", false, true, null);

        List<PaymentConceptView> items = concepts.list(actor, requestId, 0, 50, null, null, null).items();

        assertThat(items).extracting(PaymentConceptView::code).containsExactly("BUS", "COLEG");
        assertThat(items.get(0).taxRateId()).isNull();
        assertThat(items.get(0).taxRateCode()).isNull();
        assertThat(items.get(1).taxRateCode()).isEqualTo("ISV");
        assertThat(items.get(1).taxRateName()).isEqualTo("Impuesto sobre ventas");
    }

    // --- list ---

    @Test
    void theListIsScopedToTheInstitutionSortedByCodeByDefaultAndFiltered() {
        arranged(institution, "b-fee", "Beta", PaymentCategory.FINE, true);
        arranged(institution, "A-tuition", "Zeta", PaymentCategory.TUITION, true);
        arranged(institution, "C-bus", "Alfa", PaymentCategory.TRANSPORT, false);
        arranged(new InstitutionId(UUID.randomUUID()), "OTHER", "Otra", PaymentCategory.OTHER, true);

        assertThat(concepts.list(actor, requestId, 0, 50, null, null, null).items())
                .extracting(PaymentConceptView::code).containsExactly("A-tuition", "b-fee", "C-bus");
        assertThat(concepts.list(actor, requestId, 0, 50, "name,desc", null, null).items())
                .extracting(PaymentConceptView::name).containsExactly("Zeta", "Beta", "Alfa");
        assertThat(concepts.list(actor, requestId, 0, 50, null, "FINE", null).items())
                .extracting(PaymentConceptView::code).containsExactly("b-fee");
        assertThat(concepts.list(actor, requestId, 0, 50, null, null, false).items())
                .extracting(PaymentConceptView::code).containsExactly("C-bus");
        assertThat(concepts.list(actor, requestId, 0, 50, null, "TUITION", false).items()).isEmpty();
        assertThat(concepts.list(actor, requestId, 0, 2, null, null, null).total()).isEqualTo(3);
        assertThat(concepts.list(actor, requestId, 0, 2, null, null, null).items()).hasSize(2);
        assertThat(audit).isEmpty();
    }

    @Test
    void theListRefusesAFieldCategoryOrSizeOutsideItsSet() {
        assertThatThrownBy(() -> concepts.list(actor, requestId, 0, 50, "isActive", null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.list(actor, requestId, 0, 50, null, "NOPE", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.list(actor, requestId, 0, 201, null, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> concepts.list(actor, requestId, -1, 50, null, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    private record Key(InstitutionId institution, UUID id) {
    }

    /** An in-memory port that, like the database, rejects a code that differs only by capitals in the institution. */
    private static final class InMemoryConcepts implements PaymentConceptRepository {

        private final Map<Key, PaymentConcept> rows = new LinkedHashMap<>();

        @Override
        public Optional<PaymentConcept> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(new Key(institution, id)));
        }

        @Override
        public Optional<PaymentConcept> lockShareById(InstitutionId institution, UUID id) {
            return findById(institution, id);
        }

        @Override
        public void insert(InstitutionId institution, PaymentConcept concept) {
            boolean taken = rows.entrySet().stream().anyMatch(entry -> entry.getKey().institution().equals(institution)
                    && entry.getValue().code().equalsIgnoreCase(concept.code()));
            if (taken) {
                throw new DuplicateCodeException();
            }
            rows.put(new Key(institution, concept.id()), concept);
        }

        @Override
        public void update(InstitutionId institution, PaymentConcept concept, long expectedVersion) {
            Key key = new Key(institution, concept.id());
            if (!rows.containsKey(key)) {
                throw new ResourceNotFoundException();
            }
            if (rows.get(key).version() != expectedVersion) {
                throw new StaleVersionException();
            }
            rows.put(key, concept);
        }

        @Override
        public Page<PaymentConcept> list(InstitutionId institution, PageRequest request,
                PaymentConceptFilter filter) {
            Comparator<PaymentConcept> order = request.sortField().equals("code")
                    ? Comparator.comparing(concept -> concept.code().toLowerCase())
                    : Comparator.comparing(PaymentConcept::name);
            List<PaymentConcept> matching = rows.entrySet().stream()
                    .filter(entry -> entry.getKey().institution().equals(institution))
                    .map(Map.Entry::getValue)
                    .filter(concept -> filter.category() == null || concept.category() == filter.category())
                    .filter(concept -> filter.active() == null || concept.active() == filter.active())
                    .sorted(request.ascending() ? order : order.reversed()).toList();
            int from = (int) Math.min(request.offset(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
        }
    }
}
