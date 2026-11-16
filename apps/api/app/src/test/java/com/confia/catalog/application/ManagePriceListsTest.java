package com.confia.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.domain.InactiveReferenceException;
import com.confia.catalog.domain.InvalidCatalogValueException;
import com.confia.catalog.domain.PaymentCategory;
import com.confia.catalog.domain.PaymentConcept;
import com.confia.catalog.domain.PriceList;
import com.confia.catalog.domain.PriceListArchivedException;
import com.confia.catalog.domain.PriceListItem;
import com.confia.catalog.domain.PriceValidityOverlapException;
import com.confia.catalog.domain.ResourceNotFoundException;
import com.confia.catalog.domain.StaleVersionException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.AcademicStructureDirectory;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The use case of the price lists on in-memory ports and the stub of the transactional component
 * (docs/cortes/catalog-price-lists.md): what is written, what is audited, and what is refused leaves neither a row nor an
 * entry. The database-level rules (the exclusion, the immutability, the isolation) are proven by the integration tests.
 */
class ManagePriceListsTest {

    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final UUID year = UUID.randomUUID();
    private final UUID modality = UUID.randomUUID();
    private final UUID grade = UUID.randomUUID();
    private final Structure structure = new Structure();
    private final Lists lists = new Lists();
    private final Items items = new Items();
    private final Concepts concepts = new Concepts();
    private final ManagePriceLists service =
            new ManagePriceLists(runner, lists, items, concepts, structure, auditWriter, clock);

    private PriceListView institutionList() {
        structure.years.add(new Ref(institution, year));
        return service.create(actor, requestId, year, "2027 - Institucion", "INSTITUTION", null);
    }

    private PaymentConcept concept(boolean active) {
        PaymentConcept concept = new PaymentConcept(UUID.randomUUID(), "COLEG", "Colegiatura",
                PaymentCategory.TUITION, true, true, active, 0, NOW, NOW);
        concepts.rows.put(new Key(institution, concept.id()), concept);
        return concept;
    }

    private PriceListItemView add(UUID list, UUID concept, String amount, String from, String to) {
        return service.addPrice(actor, requestId, list, concept, amount, "HNL", LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to));
    }

    // --- create ---

    @Test
    void creatingAListStoresItAtVersionZeroAndWritesOneCreatedEntryInTheSameTransaction() {
        structure.years.add(new Ref(institution, year));

        PriceListView view = service.create(actor, requestId, year, "2027 - Institucion", "INSTITUTION", null);

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.version()).isZero();
        assertThat(view.scope()).isEqualTo("INSTITUTION");
        assertThat(view.scopeRefId()).isNull();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("catalog.price_list.created");
            assertThat(entry.entityType()).isEqualTo("catalog.price_list");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"name\":\"2027 - Institucion\",\"academicYearId\":\""
                    + year + "\",\"scope\":\"INSTITUTION\",\"status\":\"ACTIVE\",\"version\":0}");
        });
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aListOfAModalityOrAGradeNeedsAReferenceOfTheInstitutionAndItsAuditCarriesIt() {
        structure.years.add(new Ref(institution, year));
        structure.modalities.add(new Ref(institution, modality));
        structure.grades.add(new Ref(institution, grade));

        PriceListView byModality = service.create(actor, requestId, year, "Bachillerato", "MODALITY", modality);
        PriceListView byGrade = service.create(actor, requestId, year, "Décimo", "GRADE", grade);

        assertThat(byModality.scopeRefId()).isEqualTo(modality);
        assertThat(byGrade.scopeRefId()).isEqualTo(grade);
        assertThat(audit.get(0).afterValue()).contains("\"scopeRefId\":\"" + modality + "\"");
        assertThat(audit.get(1).afterValue()).contains("\"scope\":\"GRADE\"");
    }

    @Test
    void aYearModalityOrGradeOfAnotherInstitutionOrThatDoesNotExistIsNotFoundAndLeavesNoRowNorEntry() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        structure.years.add(new Ref(other, year));
        structure.modalities.add(new Ref(other, modality));
        structure.grades.add(new Ref(other, grade));

        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "INSTITUTION", null))
                .isInstanceOf(ResourceNotFoundException.class);
        structure.years.add(new Ref(institution, year));
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "MODALITY", modality))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", grade))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, UUID.randomUUID(), "X", "INSTITUTION", null))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(lists.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void aBadScopeNameOrReferenceCombinationIsRefusedBeforeAnythingIsWritten() {
        structure.years.add(new Ref(institution, year));
        structure.modalities.add(new Ref(institution, modality));

        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "ALL", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "MODALITY", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "INSTITUTION", modality))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, " ", "INSTITUTION", null))
                .isInstanceOf(InvalidCatalogValueException.class);

        assertThat(lists.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    // --- update ---

    @Test
    void anEditChangesNameAndStatusAdvancesTheVersionAndAuditsBeforeAndAfter() {
        PriceListView created = institutionList();
        audit.clear();

        PriceListView archived = service.update(actor, requestId, created.id(), 0, "2027 cerrada", "ARCHIVED");

        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.version()).isEqualTo(1);
        assertThat(archived.scope()).isEqualTo("INSTITUTION");
        assertThat(archived.academicYearId()).isEqualTo(year);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("catalog.price_list.updated");
            assertThat(entry.beforeValue()).contains("\"status\":\"ACTIVE\"").contains("\"version\":0");
            assertThat(entry.afterValue()).contains("\"status\":\"ARCHIVED\"").contains("\"version\":1")
                    .contains("\"name\":\"2027 cerrada\"");
        });
    }

    @Test
    void anEditWithAStaleVersionIsRefusedAndLeavesTheListAndTheAuditUntouched() {
        PriceListView created = institutionList();
        service.update(actor, requestId, created.id(), 0, "Primero", "ACTIVE");
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Segundo", "ARCHIVED"))
                .isInstanceOf(StaleVersionException.class);

        assertThat(audit).isEmpty();
        assertThat(lists.rows.values()).singleElement().satisfies(stored -> {
            assertThat(stored.name()).isEqualTo("Primero");
            assertThat(stored.version()).isEqualTo(1);
        });
    }

    @Test
    void anUnknownListOrOneOfAnotherInstitutionIsNotFoundOnEditReadAndAddAndLeavesNoEntry() {
        PriceList foreign = PriceList.create(UUID.randomUUID(), year, "Ajena", com.confia.catalog.domain.PriceListScope.INSTITUTION,
                null, NOW);
        lists.rows.put(new Key(new InstitutionId(UUID.randomUUID()), foreign.id()), foreign);
        PaymentConcept concept = concept(true);

        assertThatThrownBy(() -> service.update(actor, requestId, foreign.id(), 0, "X", "ACTIVE"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(actor, requestId, foreign.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> add(foreign.id(), concept.id(), "1.00", "2027-01-01", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, UUID.randomUUID(), 0, "X", "ACTIVE"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(audit).isEmpty();
        assertThat(items.rows).isEmpty();
    }

    // --- adding a price ---

    @Test
    void theAcceptanceCriterionOneThousandFiveHundredFromJanuaryThenOneThousandSixHundredFiftyFromJulyClosesTheFirstOnJuneThirtieth() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);
        audit.clear();

        PriceListItemView first = add(list.id(), colegiatura.id(), "1500", "2027-01-01", null);
        PriceListItemView second = add(list.id(), colegiatura.id(), "1650", "2027-07-01", null);

        assertThat(first.amount()).isEqualTo("1500.00");
        assertThat(first.currency()).isEqualTo("HNL");
        assertThat(first.validTo()).isNull();
        assertThat(second.amount()).isEqualTo("1650.00");
        assertThat(second.validTo()).isNull();
        PriceListDetailView detail = service.get(actor, requestId, list.id());
        assertThat(detail.items()).extracting(PriceListItemView::amount, PriceListItemView::validFrom,
                PriceListItemView::validTo).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1500.00", LocalDate.parse("2027-01-01"),
                                LocalDate.parse("2027-06-30")),
                        org.assertj.core.groups.Tuple.tuple("1650.00", LocalDate.parse("2027-07-01"), null));
        assertThat(audit).extracting(AuditEntry::action)
                .containsExactly("catalog.price_list_item.created", "catalog.price_list_item.created");
        assertThat(audit.get(0).afterValue()).doesNotContain("closedPrevious");
        assertThat(audit.get(1).entityType()).isEqualTo("catalog.price_list_item");
        assertThat(audit.get(1).afterValue()).contains("\"amount\":\"1650.0000\"").contains("\"currency\":\"HNL\"")
                .contains("\"validFrom\":\"2027-07-01\"").contains("\"closedPreviousItemId\":\"" + first.id() + "\"")
                .contains("\"closedPreviousValidTo\":\"2027-06-30\"");
        assertThat(items.closedCalls).isEqualTo(1);
    }

    @Test
    void thePriceOfPresentationIsRoundedByTheServerHalfUpToTheMinorUnitWhileTheStoredPriceKeepsFourDecimals() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);

        PriceListItemView up = add(list.id(), colegiatura.id(), "10.005", "2027-01-01", "2027-01-31");
        PriceListItemView down = add(list.id(), colegiatura.id(), "10.0049", "2027-02-01", "2027-02-28");
        PriceListItemView exact = add(list.id(), colegiatura.id(), "7", "2027-03-01", "2027-03-31");

        assertThat(up.amount()).isEqualTo("10.01");
        assertThat(down.amount()).isEqualTo("10.00");
        assertThat(exact.amount()).isEqualTo("7.00");
        assertThat(items.rows.values()).extracting(item -> item.price().toPlainString())
                .containsExactly("10.0050", "10.0049", "7.0000");
    }

    @Test
    void aPriceOfAnotherConceptOrAListWithNoOpenValidityNeedsNoClosing() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);
        PaymentConcept matricula = concept(true);

        add(list.id(), colegiatura.id(), "1500", "2027-01-01", null);
        add(list.id(), matricula.id(), "800", "2027-01-01", null);

        assertThat(items.closedCalls).isZero();
        assertThat(items.rows).hasSize(2);
    }

    @Test
    void aReplacementThatStartsOnOrBeforeTheOpenPriceIsAnOverlapAndChangesNothing() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);
        add(list.id(), colegiatura.id(), "1500", "2027-03-01", null);
        audit.clear();

        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "1650", "2027-03-01", null))
                .isInstanceOf(PriceValidityOverlapException.class);
        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "1650", "2027-01-01", null))
                .isInstanceOf(PriceValidityOverlapException.class);
        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "1650", "2027-04-01", "2027-04-30"))
                .isInstanceOf(PriceValidityOverlapException.class);

        assertThat(items.rows).hasSize(1);
        assertThat(items.rows.values().iterator().next().validTo()).isNull();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void anOverlapWithAClosedValidityThatOnlyTheDatabaseSeesIsRefusedAndLeavesNoEntry() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);
        add(list.id(), colegiatura.id(), "100", "2026-01-01", "2026-12-31");
        items.refuseOverlapsOnInsert = true;
        audit.clear();

        // There is no open validity to compare with, so the use case inserts and the exclusion constraint refuses.
        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "300", "2026-06-01", null))
                .isInstanceOf(PriceValidityOverlapException.class);

        assertThat(items.rows).hasSize(1);
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void anArchivedListTakesNoPriceButStillShowsItsHistory() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);
        add(list.id(), colegiatura.id(), "1500", "2027-01-01", null);
        service.update(actor, requestId, list.id(), 0, "Archivada", "ARCHIVED");
        audit.clear();

        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "1650", "2027-07-01", null))
                .isInstanceOf(PriceListArchivedException.class);

        assertThat(service.get(actor, requestId, list.id()).items()).hasSize(1);
        assertThat(audit).isEmpty();
    }

    @Test
    void anInactiveConceptOrOneOfAnotherInstitutionOrUnknownTakesNoPrice() {
        PriceListView list = institutionList();
        PaymentConcept inactive = concept(false);
        PaymentConcept foreign = new PaymentConcept(UUID.randomUUID(), "AJENO", "Ajeno", PaymentCategory.OTHER,
                false, false, true, 0, NOW, NOW);
        concepts.rows.put(new Key(new InstitutionId(UUID.randomUUID()), foreign.id()), foreign);

        assertThatThrownBy(() -> add(list.id(), inactive.id(), "1.00", "2027-01-01", null))
                .isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> add(list.id(), foreign.id(), "1.00", "2027-01-01", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> add(list.id(), UUID.randomUUID(), "1.00", "2027-01-01", null))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(items.rows).isEmpty();
    }

    @Test
    void anAmountThatIsZeroNegativeOrWithTooManyDecimalsAnUnknownCurrencyAndAnEndBeforeTheStartAreRefused() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);

        for (String amount : new String[] {"0", "0.00", "-1500", "1500.00001", "abc"}) {
            assertThatThrownBy(() -> add(list.id(), colegiatura.id(), amount, "2027-01-01", null))
                    .as("amount %s", amount).isInstanceOf(InvalidCatalogValueException.class);
        }
        assertThatThrownBy(() -> service.addPrice(actor, requestId, list.id(), colegiatura.id(), "1.00", "EUR",
                LocalDate.parse("2027-01-01"), null)).isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> add(list.id(), colegiatura.id(), "1.00", "2027-02-01", "2027-01-31"))
                .isInstanceOf(InvalidCatalogValueException.class);

        assertThat(items.rows).isEmpty();
        assertThat(audit).hasSize(1);
    }

    @Test
    void theCreatorOfAPriceIsTheStaffMemberOfTheToken() {
        PriceListView list = institutionList();
        PaymentConcept colegiatura = concept(true);

        add(list.id(), colegiatura.id(), "1500", "2027-01-01", null);

        assertThat(items.rows.values()).extracting(PriceListItem::createdBy).containsExactly(actor.accountId());
    }

    // --- read and list ---

    @Test
    void theListIsScopedToTheInstitutionSortedByNameByDefaultAndFiltered() {
        structure.years.add(new Ref(institution, year));
        UUID otherYear = UUID.randomUUID();
        structure.years.add(new Ref(institution, otherYear));
        structure.modalities.add(new Ref(institution, modality));
        service.create(actor, requestId, year, "b-lista", "INSTITUTION", null);
        PriceListView archived = service.create(actor, requestId, year, "A-lista", "MODALITY", modality);
        service.update(actor, requestId, archived.id(), 0, "A-lista", "ARCHIVED");
        service.create(actor, requestId, otherYear, "C-lista", "INSTITUTION", null);
        PriceList foreign = PriceList.create(UUID.randomUUID(), year, "Ajena", com.confia.catalog.domain.PriceListScope.INSTITUTION,
                null, NOW);
        lists.rows.put(new Key(new InstitutionId(UUID.randomUUID()), foreign.id()), foreign);
        audit.clear();

        assertThat(service.list(actor, requestId, 0, 50, null, null, null, null).items())
                .extracting(PriceListView::name).containsExactly("A-lista", "b-lista", "C-lista");
        assertThat(service.list(actor, requestId, 0, 50, "name,desc", null, null, null).items())
                .extracting(PriceListView::name).containsExactly("C-lista", "b-lista", "A-lista");
        assertThat(service.list(actor, requestId, 0, 50, null, year, null, null).items())
                .extracting(PriceListView::name).containsExactly("A-lista", "b-lista");
        assertThat(service.list(actor, requestId, 0, 50, null, null, "MODALITY", null).items())
                .extracting(PriceListView::name).containsExactly("A-lista");
        assertThat(service.list(actor, requestId, 0, 50, null, null, null, "ARCHIVED").items())
                .extracting(PriceListView::name).containsExactly("A-lista");
        assertThat(service.list(actor, requestId, 0, 50, null, year, "INSTITUTION", "ARCHIVED").items()).isEmpty();
        assertThat(service.list(actor, requestId, 0, 2, null, null, null, null).total()).isEqualTo(3);
        assertThat(service.list(actor, requestId, 0, 2, null, null, null, null).items()).hasSize(2);
        assertThat(audit).isEmpty();
    }

    @Test
    void theListRefusesAFieldScopeStatusOrSizeOutsideItsSet() {
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, "version", null, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, null, null, "ALL", null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, null, null, null, "OPEN"))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 201, null, null, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, -1, 50, null, null, null, null))
                .isInstanceOf(InvalidCatalogValueException.class);
    }

    // --- Ports ---

    private record Key(InstitutionId institution, UUID id) {
    }

    private record Ref(InstitutionId institution, UUID id) {
    }

    private static final class Structure implements AcademicStructureDirectory {

        private final Set<Ref> years = new HashSet<>();
        private final Set<Ref> modalities = new HashSet<>();
        private final Set<Ref> grades = new HashSet<>();

        @Override
        public boolean academicYearExists(InstitutionId institutionId, UUID academicYearId) {
            return years.contains(new Ref(institutionId, academicYearId));
        }

        @Override
        public boolean modalityExists(InstitutionId institutionId, UUID modalityId) {
            return modalities.contains(new Ref(institutionId, modalityId));
        }

        @Override
        public boolean gradeExists(InstitutionId institutionId, UUID gradeId) {
            return grades.contains(new Ref(institutionId, gradeId));
        }
    }

    private static final class Concepts implements PaymentConceptRepository {

        private final Map<Key, PaymentConcept> rows = new LinkedHashMap<>();

        @Override
        public Optional<PaymentConcept> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(new Key(institution, id)));
        }

        @Override
        public void insert(InstitutionId institution, PaymentConcept concept) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(InstitutionId institution, PaymentConcept concept, long expectedVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Page<PaymentConcept> list(InstitutionId institution, PageRequest request, PaymentConceptFilter filter) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Lists implements PriceListRepository {

        private final Map<Key, PriceList> rows = new LinkedHashMap<>();

        @Override
        public Optional<PriceList> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(new Key(institution, id)));
        }

        @Override
        public Optional<PriceList> lockById(InstitutionId institution, UUID id) {
            return findById(institution, id);
        }

        @Override
        public void insert(InstitutionId institution, PriceList list) {
            rows.put(new Key(institution, list.id()), list);
        }

        @Override
        public void update(InstitutionId institution, PriceList list, long expectedVersion) {
            Key key = new Key(institution, list.id());
            if (!rows.containsKey(key)) {
                throw new ResourceNotFoundException();
            }
            if (rows.get(key).version() != expectedVersion) {
                throw new StaleVersionException();
            }
            rows.put(key, list);
        }

        @Override
        public Page<PriceList> list(InstitutionId institution, PageRequest request, PriceListFilter filter) {
            Comparator<PriceList> order = request.sortField().equals("name")
                    ? Comparator.comparing(list -> list.name().toLowerCase())
                    : Comparator.comparing(PriceList::createdAt);
            List<PriceList> matching = rows.entrySet().stream()
                    .filter(entry -> entry.getKey().institution().equals(institution))
                    .map(Map.Entry::getValue)
                    .filter(list -> filter.academicYearId() == null
                            || list.academicYearId().equals(filter.academicYearId()))
                    .filter(list -> filter.scope() == null || list.scope() == filter.scope())
                    .filter(list -> filter.status() == null || list.status() == filter.status())
                    .sorted(request.ascending() ? order : order.reversed()).toList();
            int from = (int) Math.min(request.offset(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
        }
    }

    /** In-memory prices; it can behave like the exclusion constraint of the database when asked to. */
    private static final class Items implements PriceListItemRepository {

        private final Map<UUID, PriceListItem> rows = new LinkedHashMap<>();
        private int closedCalls;
        private boolean refuseOverlapsOnInsert;

        @Override
        public List<PriceListItem> findByList(InstitutionId institution, UUID priceListId) {
            return rows.values().stream().filter(item -> item.priceListId().equals(priceListId))
                    .sorted(Comparator.comparing(PriceListItem::paymentConceptId)
                            .thenComparing(PriceListItem::validFrom)).toList();
        }

        @Override
        public Optional<PriceListItem> findOpen(InstitutionId institution, UUID priceListId, UUID conceptId) {
            return rows.values().stream().filter(item -> item.priceListId().equals(priceListId)
                    && item.paymentConceptId().equals(conceptId) && item.isOpen()).findFirst();
        }

        @Override
        public void insert(InstitutionId institution, PriceListItem item) {
            if (refuseOverlapsOnInsert && rows.values().stream().anyMatch(other -> other.priceListId()
                    .equals(item.priceListId()) && other.paymentConceptId().equals(item.paymentConceptId())
                    && other.overlaps(item))) {
                throw new PriceValidityOverlapException();
            }
            rows.put(item.id(), item);
        }

        @Override
        public void closeValidity(InstitutionId institution, UUID itemId, LocalDate lastDay) {
            closedCalls++;
            rows.put(itemId, rows.get(itemId).closedAt(lastDay));
        }
    }
}
