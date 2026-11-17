package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.DuplicatePlanItemException;
import com.confia.charges.domain.InactiveReferenceException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.PlanNotEditableException;
import com.confia.charges.domain.PlanWithoutItemsException;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.charges.domain.StaleVersionException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The use case of the charge plans on in-memory ports and the stub of the transactional component
 * (docs/cortes/charges-plans.md): what is written, what is audited, in which order the rows are locked, and what is refused
 * leaves neither a row nor an entry. The database-level rules (the triggers, the grants, the isolation) are proven by the
 * integration tests.
 */
class ManageChargePlansTest {

    /** The order the fakes were locked in, to prove the lock order of the use case. */
    private static final List<String> LOCKS = new ArrayList<>();

    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");

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
    private final UUID section = UUID.randomUUID();
    private final Structure structure = new Structure();
    private final Plans plans = new Plans();
    private final Items items = new Items();
    private final Concepts concepts = new Concepts();
    private final ManageChargePlans service =
            new ManageChargePlans(runner, plans, items, concepts, structure, auditWriter, clock);

    @BeforeEach
    void clearTheLocks() {
        LOCKS.clear();
    }

    private ChargePlanView gradePlan() {
        structure.years.add(new Ref(institution, year));
        structure.grades.add(new Ref(institution, grade));
        return service.create(actor, requestId, year, "Primaria 2027", "GRADE", grade, "MONTHLY", 5);
    }

    private UUID concept(boolean active) {
        UUID id = UUID.randomUUID();
        concepts.rows.put(new Ref(institution, id), new PaymentConceptRef(id, active));
        return id;
    }

    // --- create ---

    @Test
    void creatingAPlanStoresItAsADraftAtVersionZeroAndWritesOneCreatedEntryInTheSameTransaction() {
        ChargePlanView view = gradePlan();

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.version()).isZero();
        assertThat(view.targetScope()).isEqualTo("GRADE");
        assertThat(view.targetRefId()).isEqualTo(grade);
        assertThat(view.frequency()).isEqualTo("MONTHLY");
        assertThat(view.dueDayOfMonth()).isEqualTo(5);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.plan.created");
            assertThat(entry.entityType()).isEqualTo("charges.plan");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.institutionId()).isEqualTo(institutionUuid);
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"name\":\"Primaria 2027\",\"academicYearId\":\"" + year
                    + "\",\"targetScope\":\"GRADE\",\"targetRefId\":\"" + grade
                    + "\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,\"status\":\"DRAFT\",\"version\":0}");
        });
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void aPlanOfAModalityOrASectionNeedsATargetOfTheInstitution() {
        structure.years.add(new Ref(institution, year));
        structure.modalities.add(new Ref(institution, modality));
        structure.sections.add(new Ref(institution, section));

        ChargePlanView byModality = service.create(actor, requestId, year, "Bach", "MODALITY", modality, "ANNUAL", 1);
        ChargePlanView bySection = service.create(actor, requestId, year, "Sección A", "SECTION", section,
                "ONE_TIME", 28);

        assertThat(byModality.targetRefId()).isEqualTo(modality);
        assertThat(bySection.targetRefId()).isEqualTo(section);
        assertThat(bySection.dueDayOfMonth()).isEqualTo(28);
        assertThat(audit.get(0).afterValue()).contains("\"targetScope\":\"MODALITY\"");
        assertThat(audit.get(1).afterValue()).contains("\"targetRefId\":\"" + section + "\"");
    }

    @Test
    void aYearOrATargetOfAnotherInstitutionOrThatDoesNotExistIsNotFoundAndLeavesNoRowNorEntry() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        structure.years.add(new Ref(other, year));
        structure.modalities.add(new Ref(other, modality));
        structure.grades.add(new Ref(other, grade));
        structure.sections.add(new Ref(other, section));

        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", grade, "MONTHLY", 5))
                .isInstanceOf(ResourceNotFoundException.class);
        structure.years.add(new Ref(institution, year));
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "MODALITY", modality, "MONTHLY", 5))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", grade, "MONTHLY", 5))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "SECTION", section, "MONTHLY", 5))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", UUID.randomUUID(), "MONTHLY",
                5)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, UUID.randomUUID(), "X", "GRADE", grade, "MONTHLY",
                5)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(plans.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void aBadScopeFrequencyDueDayOrNameIsRefusedBeforeAnythingIsWritten() {
        structure.years.add(new Ref(institution, year));
        structure.grades.add(new Ref(institution, grade));

        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "INSTITUTION", grade, "MONTHLY", 5))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", grade, "WEEKLY", 5))
                .isInstanceOf(InvalidChargesValueException.class);
        for (int day : new int[] {0, 29, 31, -1}) {
            assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", grade, "MONTHLY", day))
                    .as("due day %d", day).isInstanceOf(InvalidChargesValueException.class);
        }
        assertThatThrownBy(() -> service.create(actor, requestId, year, " ", "GRADE", grade, "MONTHLY", 5))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.create(actor, requestId, year, "X", "GRADE", null, "MONTHLY", 5))
                .isInstanceOf(InvalidChargesValueException.class);

        assertThat(plans.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    // --- items ---

    @Test
    void addingAnItemLocksThePlanThenTheConceptStoresItAndAuditsItWithNoPrice() {
        ChargePlanView plan = gradePlan();
        UUID concept = concept(true);
        audit.clear();
        LOCKS.clear();

        ChargePlanItemView item = service.addItem(actor, requestId, plan.id(), concept, 10, FEBRUARY);

        assertThat(LOCKS).containsExactly("lock plan", "lock concept");
        assertThat(item.planId()).isEqualTo(plan.id());
        assertThat(item.paymentConceptId()).isEqualTo(concept);
        assertThat(item.periodsCount()).isEqualTo(10);
        assertThat(item.firstPeriodOn()).isEqualTo(FEBRUARY);
        assertThat(items.rows).hasSize(1);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.plan_item.added");
            assertThat(entry.entityType()).isEqualTo("charges.plan_item");
            assertThat(entry.entityId()).isEqualTo(item.id().toString());
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"planId\":\"" + plan.id() + "\",\"paymentConceptId\":\""
                    + concept + "\",\"periodsCount\":10,\"firstPeriodOn\":\"2027-02-01\"}");
        });
    }

    @Test
    void anItemWithAnInactiveConceptOrOneThatDoesNotExistInTheInstitutionIsRefused() {
        ChargePlanView plan = gradePlan();
        UUID inactive = concept(false);
        UUID foreign = UUID.randomUUID();
        concepts.rows.put(new Ref(new InstitutionId(UUID.randomUUID()), foreign), new PaymentConceptRef(foreign, true));
        audit.clear();

        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), inactive, 1, FEBRUARY))
                .isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), foreign, 1, FEBRUARY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), UUID.randomUUID(), 1, FEBRUARY))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(items.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aConceptRepeatedInThePlanIsRefusedAndTheFirstItemStays() {
        ChargePlanView plan = gradePlan();
        UUID concept = concept(true);
        service.addItem(actor, requestId, plan.id(), concept, 10, FEBRUARY);
        audit.clear();

        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), concept, 1, FEBRUARY))
                .isInstanceOf(DuplicatePlanItemException.class);

        assertThat(items.rows).hasSize(1);
        assertThat(audit).isEmpty();
    }

    @Test
    void aBadNumberOfPeriodsOrFirstDayIsRefusedAndLeavesNoItemNorEntry() {
        ChargePlanView plan = gradePlan();
        UUID concept = concept(true);
        audit.clear();

        for (int periods : new int[] {0, 37, -5}) {
            assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), concept, periods, FEBRUARY))
                    .as("periods %d", periods).isInstanceOf(InvalidChargesValueException.class);
        }
        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), concept, 1, LocalDate.parse("2027-02-15")))
                .isInstanceOf(InvalidChargesValueException.class);

        assertThat(items.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void anItemOfAPlanThatIsNotADraftIsRefusedBeforeTheConceptIsLocked() {
        ChargePlanView plan = gradePlan();
        UUID concept = concept(true);
        service.addItem(actor, requestId, plan.id(), concept, 10, FEBRUARY);
        service.update(actor, requestId, plan.id(), 0, "Primaria 2027", "MONTHLY", 5, "ACTIVE");
        UUID other = concept(true);
        audit.clear();
        LOCKS.clear();

        assertThatThrownBy(() -> service.addItem(actor, requestId, plan.id(), other, 1, FEBRUARY))
                .isInstanceOf(PlanNotEditableException.class);

        assertThat(LOCKS).containsExactly("lock plan");
        assertThat(items.rows).hasSize(1);
        assertThat(audit).isEmpty();
    }

    @Test
    void anItemOfAnUnknownPlanOrOfAnotherInstitutionIsNotFound() {
        ChargePlan foreign = ChargePlan.create(UUID.randomUUID(), year, "Ajeno",
                com.confia.charges.domain.PlanTargetScope.GRADE, grade, com.confia.charges.domain.PlanFrequency.MONTHLY,
                5, NOW);
        plans.rows.put(new Ref(new InstitutionId(UUID.randomUUID()), foreign.id()), foreign);
        UUID concept = concept(true);

        assertThatThrownBy(() -> service.addItem(actor, requestId, foreign.id(), concept, 1, FEBRUARY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.addItem(actor, requestId, UUID.randomUUID(), concept, 1, FEBRUARY))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.removeItem(actor, requestId, foreign.id(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(actor, requestId, foreign.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, foreign.id(), 0, "X", "MONTHLY", 5, "DRAFT"))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(audit).isEmpty();
    }

    @Test
    void removingAnItemOfADraftDeletesItAndAuditsWhatWasRemoved() {
        ChargePlanView plan = gradePlan();
        UUID concept = concept(true);
        ChargePlanItemView item = service.addItem(actor, requestId, plan.id(), concept, 10, FEBRUARY);
        audit.clear();

        service.removeItem(actor, requestId, plan.id(), item.id());

        assertThat(items.rows).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.plan_item.removed");
            assertThat(entry.entityId()).isEqualTo(item.id().toString());
            assertThat(entry.beforeValue()).contains("\"periodsCount\":10").contains(concept.toString());
            assertThat(entry.afterValue()).isNull();
        });
        assertThat(LOCKS).contains("lock plan");
    }

    @Test
    void removingAnItemThatDoesNotExistInThePlanIsNotFoundAndRemovingFromANonDraftPlanIsRefused() {
        ChargePlanView plan = gradePlan();
        ChargePlanView other = service.create(actor, requestId, year, "Otro", "GRADE", grade, "MONTHLY", 5);
        UUID concept = concept(true);
        ChargePlanItemView item = service.addItem(actor, requestId, plan.id(), concept, 10, FEBRUARY);
        audit.clear();

        assertThatThrownBy(() -> service.removeItem(actor, requestId, plan.id(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.removeItem(actor, requestId, other.id(), item.id()))
                .as("an item of another plan").isInstanceOf(ResourceNotFoundException.class);
        service.update(actor, requestId, plan.id(), 0, "Primaria 2027", "MONTHLY", 5, "ACTIVE");
        audit.clear();
        assertThatThrownBy(() -> service.removeItem(actor, requestId, plan.id(), item.id()))
                .isInstanceOf(PlanNotEditableException.class);

        assertThat(items.rows).hasSize(1);
        assertThat(audit).isEmpty();
    }

    // --- update, activation and archival ---

    @Test
    void aDraftEditChangesItsFieldsAdvancesTheVersionAndAuditsAnUpdateWithBeforeAndAfter() {
        ChargePlanView created = gradePlan();
        audit.clear();

        ChargePlanView edited = service.update(actor, requestId, created.id(), 0, "Primaria", "BIMONTHLY", 10,
                "DRAFT");

        assertThat(edited.name()).isEqualTo("Primaria");
        assertThat(edited.frequency()).isEqualTo("BIMONTHLY");
        assertThat(edited.dueDayOfMonth()).isEqualTo(10);
        assertThat(edited.status()).isEqualTo("DRAFT");
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.targetRefId()).isEqualTo(grade);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("charges.plan.updated");
            assertThat(entry.beforeValue()).contains("\"frequency\":\"MONTHLY\"").contains("\"version\":0");
            assertThat(entry.afterValue()).contains("\"frequency\":\"BIMONTHLY\"").contains("\"version\":1")
                    .contains("\"dueDayOfMonth\":10");
        });
        assertThat(LOCKS).contains("lock plan");
    }

    @Test
    void activatingAPlanWithItemsFreezesItAndAuditsAnActivationAndArchivingAuditsAnArchival() {
        ChargePlanView created = gradePlan();
        service.addItem(actor, requestId, created.id(), concept(true), 10, FEBRUARY);
        audit.clear();

        ChargePlanView active = service.update(actor, requestId, created.id(), 0, "Primaria 2027", "MONTHLY", 5,
                "ACTIVE");
        ChargePlanView archived = service.update(actor, requestId, created.id(), 1, "Primaria 2027", "MONTHLY", 5,
                "ARCHIVED");

        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertThat(archived.version()).isEqualTo(2);
        assertThat(audit).extracting(AuditEntry::action)
                .containsExactly("charges.plan.activated", "charges.plan.archived");
        assertThat(audit.get(0).beforeValue()).contains("\"status\":\"DRAFT\"");
        assertThat(audit.get(0).afterValue()).contains("\"status\":\"ACTIVE\"");
        assertThat(audit.get(1).beforeValue()).contains("\"status\":\"ACTIVE\"");
        assertThat(audit.get(1).afterValue()).contains("\"status\":\"ARCHIVED\"");
    }

    @Test
    void activatingAPlanWithoutItemsIsRefusedAndLeavesThePlanAndTheAuditUntouched() {
        ChargePlanView created = gradePlan();
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Primaria 2027", "MONTHLY", 5,
                "ACTIVE")).isInstanceOf(PlanWithoutItemsException.class);

        assertThat(plans.rows.values()).singleElement().satisfies(stored -> {
            assertThat(stored.status().name()).isEqualTo("DRAFT");
            assertThat(stored.version()).isZero();
        });
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).contains("rollback");
    }

    @Test
    void aPlanWithItsLastItemRemovedCannotBeActivatedAnymore() {
        ChargePlanView created = gradePlan();
        ChargePlanItemView item = service.addItem(actor, requestId, created.id(), concept(true), 10, FEBRUARY);
        service.removeItem(actor, requestId, created.id(), item.id());

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Primaria 2027", "MONTHLY", 5,
                "ACTIVE")).isInstanceOf(PlanWithoutItemsException.class);
    }

    @Test
    void anActivePlanKeepsItsFrequencyAndDueDayChangesItsNameAndNeverGoesBackToDraftOrReactivates() {
        ChargePlanView created = gradePlan();
        service.addItem(actor, requestId, created.id(), concept(true), 10, FEBRUARY);
        service.update(actor, requestId, created.id(), 0, "Primaria 2027", "MONTHLY", 5, "ACTIVE");
        audit.clear();

        ChargePlanView renamed = service.update(actor, requestId, created.id(), 1, "Primaria (2027)", "MONTHLY", 5,
                "ACTIVE");
        assertThat(renamed.name()).isEqualTo("Primaria (2027)");
        assertThat(audit).singleElement().extracting(AuditEntry::action).isEqualTo("charges.plan.updated");
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 2, "X", "ANNUAL", 5, "ACTIVE"))
                .isInstanceOf(PlanNotEditableException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 2, "X", "MONTHLY", 6, "ACTIVE"))
                .isInstanceOf(PlanNotEditableException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 2, "X", "MONTHLY", 5, "DRAFT"))
                .isInstanceOf(PlanNotEditableException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void anArchivedPlanCannotBeReactivatedNorEditedAndItsItemsStayAsTheyWere() {
        ChargePlanView created = gradePlan();
        service.addItem(actor, requestId, created.id(), concept(true), 10, FEBRUARY);
        service.update(actor, requestId, created.id(), 0, "Primaria 2027", "MONTHLY", 5, "ACTIVE");
        service.update(actor, requestId, created.id(), 1, "Primaria 2027", "MONTHLY", 5, "ARCHIVED");
        audit.clear();

        for (String status : new String[] {"DRAFT", "ACTIVE", "ARCHIVED"}) {
            assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 2, "Primaria 2027", "MONTHLY", 5,
                    status)).as(status).isInstanceOf(PlanNotEditableException.class);
        }
        assertThatThrownBy(() -> service.addItem(actor, requestId, created.id(), concept(true), 1, FEBRUARY))
                .isInstanceOf(PlanNotEditableException.class);

        assertThat(audit).isEmpty();
        assertThat(items.rows).hasSize(1);
    }

    @Test
    void aStaleVersionIsRefusedAndTheStoredPlanKeepsItsContent() {
        ChargePlanView created = gradePlan();
        service.update(actor, requestId, created.id(), 0, "Primero", "MONTHLY", 5, "DRAFT");
        audit.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "Segundo", "MONTHLY", 5, "DRAFT"))
                .isInstanceOf(StaleVersionException.class);

        assertThat(audit).isEmpty();
        assertThat(plans.rows.values()).singleElement().satisfies(stored -> {
            assertThat(stored.name()).isEqualTo("Primero");
            assertThat(stored.version()).isEqualTo(1);
        });
    }

    @Test
    void aBadStatusOrFrequencyOnEditIsRefusedBeforeTheTransactionOpens() {
        ChargePlanView created = gradePlan();
        transactionCalls.clear();

        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "X", "MONTHLY", 5, "DELETED"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "X", "WEEKLY", 5, "DRAFT"))
                .isInstanceOf(InvalidChargesValueException.class);

        assertThat(transactionCalls).isEmpty();
    }

    @Test
    void aDueDayOutsideOneToTwentyEightOnEditIsRefusedAndLeavesThePlanAndTheAuditUntouched() {
        ChargePlanView created = gradePlan();
        audit.clear();

        for (int day : new int[] {0, 29, 31}) {
            assertThatThrownBy(() -> service.update(actor, requestId, created.id(), 0, "X", "MONTHLY", day, "DRAFT"))
                    .as("due day %d", day).isInstanceOf(InvalidChargesValueException.class);
        }

        assertThat(audit).isEmpty();
        assertThat(plans.rows.values()).singleElement().satisfies(stored -> {
            assertThat(stored.dueDayOfMonth()).isEqualTo(5);
            assertThat(stored.version()).isZero();
        });
    }

    // --- reads ---

    @Test
    void theDetailHoldsThePlanAndItsItemsInTheOrderTheyWereAddedAndAReadWritesNoEntry() {
        ChargePlanView created = gradePlan();
        UUID tuition = concept(true);
        UUID enrollment = concept(true);
        service.addItem(actor, requestId, created.id(), tuition, 10, FEBRUARY);
        service.addItem(actor, requestId, created.id(), enrollment, 1, FEBRUARY);
        transactionCalls.clear();

        ChargePlanDetailView detail = service.get(actor, requestId, created.id());

        assertThat(detail.plan().id()).isEqualTo(created.id());
        assertThat(detail.items()).extracting(ChargePlanItemView::paymentConceptId)
                .containsExactly(tuition, enrollment);
        assertThat(audit).extracting(AuditEntry::action).doesNotContain("charges.plan.read");
    }

    @Test
    void theListFiltersByYearScopeAndStatusAndSortsAndPagesAndOnlyShowsTheInstitutionOfTheActor() {
        ChargePlanView first = gradePlan();
        structure.modalities.add(new Ref(institution, modality));
        service.create(actor, requestId, year, "Bachillerato", "MODALITY", modality, "ANNUAL", 1);
        service.addItem(actor, requestId, first.id(), concept(true), 10, FEBRUARY);
        service.update(actor, requestId, first.id(), 0, "Primaria 2027", "MONTHLY", 5, "ACTIVE");
        ChargePlan foreign = ChargePlan.create(UUID.randomUUID(), year, "Ajeno",
                com.confia.charges.domain.PlanTargetScope.GRADE, grade, com.confia.charges.domain.PlanFrequency.MONTHLY,
                5, NOW);
        plans.rows.put(new Ref(new InstitutionId(UUID.randomUUID()), foreign.id()), foreign);

        Page<ChargePlanView> all = service.list(actor, requestId, 0, 50, null, null, null, null);
        Page<ChargePlanView> modalities = service.list(actor, requestId, 0, 50, null, year, "MODALITY", null);
        Page<ChargePlanView> actives = service.list(actor, requestId, 0, 50, "name,desc", null, "", "ACTIVE");
        Page<ChargePlanView> paged = service.list(actor, requestId, 1, 1, "name,asc", null, null, null);

        assertThat(all.items()).extracting(ChargePlanView::name).containsExactly("Bachillerato", "Primaria 2027");
        assertThat(all.total()).isEqualTo(2);
        assertThat(modalities.items()).extracting(ChargePlanView::name).containsExactly("Bachillerato");
        assertThat(actives.items()).extracting(ChargePlanView::name).containsExactly("Primaria 2027");
        assertThat(paged.items()).extracting(ChargePlanView::name).containsExactly("Primaria 2027");
        assertThat(paged.total()).isEqualTo(2);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, "dueDay", null, null, null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 201, null, null, null, null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, null, null, "INSTITUTION", null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> service.list(actor, requestId, 0, 50, null, null, null, "DELETED"))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    // --- Fakes ---

    private record Ref(InstitutionId institution, UUID id) {
    }

    private static final class Structure implements AcademicStructureDirectory {

        private final Set<Ref> years = new HashSet<>();
        private final Set<Ref> modalities = new HashSet<>();
        private final Set<Ref> grades = new HashSet<>();
        private final Set<Ref> sections = new HashSet<>();

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

        @Override
        public boolean sectionExists(InstitutionId institutionId, UUID sectionId) {
            return sections.contains(new Ref(institutionId, sectionId));
        }
    }

    private static final class Concepts implements PaymentConceptDirectory {

        private final Map<Ref, PaymentConceptRef> rows = new LinkedHashMap<>();

        @Override
        public Optional<PaymentConceptRef> lockShare(InstitutionId institutionId, UUID paymentConceptId) {
            LOCKS.add("lock concept");
            return Optional.ofNullable(rows.get(new Ref(institutionId, paymentConceptId)));
        }
    }

    private static final class Plans implements ChargePlanRepository {

        private final Map<Ref, ChargePlan> rows = new LinkedHashMap<>();

        @Override
        public Optional<ChargePlan> findById(InstitutionId institution, UUID id) {
            return Optional.ofNullable(rows.get(new Ref(institution, id)));
        }

        @Override
        public Optional<ChargePlan> lockById(InstitutionId institution, UUID id) {
            LOCKS.add("lock plan");
            return findById(institution, id);
        }

        @Override
        public void insert(InstitutionId institution, ChargePlan plan) {
            rows.put(new Ref(institution, plan.id()), plan);
        }

        @Override
        public void update(InstitutionId institution, ChargePlan plan, long expectedVersion) {
            Ref key = new Ref(institution, plan.id());
            if (!rows.containsKey(key)) {
                throw new ResourceNotFoundException();
            }
            if (rows.get(key).version() != expectedVersion) {
                throw new StaleVersionException();
            }
            rows.put(key, plan);
        }

        @Override
        public Page<ChargePlan> list(InstitutionId institution, PageRequest request, ChargePlanFilter filter) {
            Comparator<ChargePlan> order = request.sortField().equals("name")
                    ? Comparator.comparing(plan -> plan.name().toLowerCase())
                    : Comparator.comparing(ChargePlan::createdAt);
            List<ChargePlan> matching = rows.entrySet().stream()
                    .filter(entry -> entry.getKey().institution().equals(institution))
                    .map(Map.Entry::getValue)
                    .filter(plan -> filter.academicYearId() == null
                            || plan.academicYearId().equals(filter.academicYearId()))
                    .filter(plan -> filter.targetScope() == null || plan.targetScope() == filter.targetScope())
                    .filter(plan -> filter.status() == null || plan.status() == filter.status())
                    .sorted(request.ascending() ? order : order.reversed()).toList();
            int from = (int) Math.min(request.offset(), matching.size());
            int to = Math.min(from + request.size(), matching.size());
            return new Page<>(matching.subList(from, to), matching.size(), request.page(), request.size());
        }
    }

    private static final class Items implements ChargePlanItemRepository {

        private final Map<Ref, ChargePlanItem> rows = new LinkedHashMap<>();

        @Override
        public List<ChargePlanItem> findByPlan(InstitutionId institution, UUID planId) {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getKey().institution().equals(institution))
                    .map(Map.Entry::getValue)
                    .filter(item -> item.planId().equals(planId)).toList();
        }

        @Override
        public Optional<ChargePlanItem> find(InstitutionId institution, UUID planId, UUID itemId) {
            return Optional.ofNullable(rows.get(new Ref(institution, itemId)))
                    .filter(item -> item.planId().equals(planId));
        }

        @Override
        public long countByPlan(InstitutionId institution, UUID planId) {
            return findByPlan(institution, planId).size();
        }

        @Override
        public void insert(InstitutionId institution, ChargePlanItem item) {
            rows.put(new Ref(institution, item.id()), item);
        }

        @Override
        public boolean delete(InstitutionId institution, UUID planId, UUID itemId) {
            return find(institution, planId, itemId).isPresent() && rows.remove(new Ref(institution, itemId)) != null;
        }
    }
}
