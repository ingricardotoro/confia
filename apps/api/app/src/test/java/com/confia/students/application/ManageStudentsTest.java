package com.confia.students.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.DuplicateStudentCodeException;
import com.confia.students.domain.InvalidStudentException;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.Student;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ManageStudentsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");
    private static final LocalDate BIRTH = LocalDate.of(2012, 5, 20);
    private static final String ID = "0801-2012-01234";
    private static final String ID_NORMALIZED = "0801201201234";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryStudents store = new InMemoryStudents();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final ManageStudents students =
            new ManageStudents(runner, store.students, store.protector, auditWriter, clock);

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private StudentView create(String code, String first, String last, String nationalId) {
        return students.create(actor, requestId, code, first, last, BIRTH, "F", nationalId);
    }

    private StudentView update(UUID id, String first, String last, String status, String nationalId) {
        return students.update(actor, requestId, id, first, last, BIRTH, "F", status, nationalId);
    }

    private boolean committed() {
        return transactionCalls.contains("commit") && !transactionCalls.contains("rollback");
    }

    private boolean rolledBack() {
        return transactionCalls.contains("rollback") && !transactionCalls.contains("commit");
    }

    private static JsonNode json(String document) {
        return JsonMapper.builder().build().readTree(document);
    }

    // --- Creation and the generated code ---

    @Test
    void creatingWithoutACodeGeneratesTheSequentialCodesOfTheInstitution() {
        assertThat(create(null, "Ana", "Perez", null).studentCode()).isEqualTo("E-000001");
        assertThat(create(null, "Luis", "Lopez", null).studentCode()).isEqualTo("E-000002");
        assertThat(create(null, "Eva", "Mejia", null).studentCode()).isEqualTo("E-000003");
        assertThat(committed()).isTrue();
    }

    @Test
    void theCounterOfEachInstitutionIsIndependent() {
        create(null, "Ana", "Perez", null);
        create(null, "Luis", "Lopez", null);

        StudentView other = students.create(actorOf(UUID.randomUUID()), requestId, null, "Eva", "Mejia", BIRTH,
                "F", null);

        assertThat(other.studentCode()).isEqualTo("E-000001");
    }

    @Test
    void aBlankCodeIsTreatedAsMissing() {
        assertThat(create("   ", "Ana", "Perez", null).studentCode()).isEqualTo("E-000001");
        assertThat(create("", "Luis", "Lopez", null).studentCode()).isEqualTo("E-000002");
    }

    @Test
    void aTypedCodeIsKeptStrippedAndDoesNotConsumeTheCounter() {
        StudentView typed = create("  MAT-77 ", "Ana", "Perez", null);

        assertThat(typed.studentCode()).isEqualTo("MAT-77");
        assertThat(store.events).doesNotContain("sequence");
        assertThat(create(null, "Luis", "Lopez", null).studentCode()).isEqualTo("E-000001");
    }

    @Test
    void aGeneratedCodeNeverCollidesWithACodeTypedByHand() {
        create("E-000001", "Ana", "Perez", null);
        create("E-000002", "Luis", "Lopez", null);

        assertThat(create(null, "Eva", "Mejia", null).studentCode()).isEqualTo("E-000003");
        assertThat(store.rowsOf(institution)).hasSize(3);
    }

    @Test
    void aCodeAlreadyTakenIsADuplicateAndLeavesNoEntryAndNoRow() {
        create("MAT-1", "Ana", "Perez", null);
        audit.clear();
        transactionCalls.clear();

        assertThatThrownBy(() -> create("MAT-1", "Luis", "Lopez", null))
                .isInstanceOf(DuplicateStudentCodeException.class);

        assertThat(audit).isEmpty();
        assertThat(store.rowsOf(institution)).hasSize(1);
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void theSameCodeInAnotherInstitutionIsNotADuplicate() {
        students.create(actorOf(UUID.randomUUID()), requestId, "MAT-1", "Eva", "Mejia", BIRTH, "F", null);

        assertThat(create("MAT-1", "Ana", "Perez", null).studentCode()).isEqualTo("MAT-1");
    }

    @Test
    void creatingStoresNamesStrippedAndTheStudentIsActive() {
        StudentView view = create(null, "  Ana Maria ", " Perez  ", null);

        assertThat(view.firstName()).isEqualTo("Ana Maria");
        assertThat(view.lastName()).isEqualTo("Perez");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.sex()).isEqualTo("F");
        assertThat(view.birthDate()).isEqualTo(BIRTH);
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(view.hasNationalId()).isFalse();
    }

    @Test
    void theInstantOfTheWriteIsKeptToTheMicrosecondSoTheAnswerEqualsWhatAReadReturns() {
        Instant withNanos = Instant.parse("2026-03-01T15:00:00.123456789Z");
        ManageStudents precise = new ManageStudents(runner, store.students, store.protector, auditWriter,
                Clock.fixed(withNanos, ZoneOffset.UTC));

        StudentView created = precise.create(actor, requestId, null, "Ana", "Perez", BIRTH, "F", null);

        assertThat(created.createdAt()).isEqualTo(Instant.parse("2026-03-01T15:00:00.123456Z"));
        assertThat(created.updatedAt()).isEqualTo(created.createdAt());
        assertThat(precise.get(actor, requestId, created.id())).isEqualTo(created);
    }

    @Test
    void anInvalidStudentIsRefusedWithNoRowNoEntryAndNothingIsStored() {
        assertThatThrownBy(() -> create(null, "  ", "Perez", null)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.create(actor, requestId, null, "Ana", "Perez", LocalDate.of(2026, 3, 2),
                "F", null)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.create(actor, requestId, null, "Ana", "Perez", LocalDate.of(1989, 12, 31),
                "F", null)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.create(actor, requestId, null, "Ana", "Perez", BIRTH, "X", null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> create(null, "Ana", "Perez", "short")).isInstanceOf(InvalidStudentException.class);

        assertThat(store.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    // --- The document ---

    @Test
    void aDocumentIsProtectedBeforeItIsStoredAndOnlyThatThereIsOneIsReturned() {
        StudentView view = create(null, "Ana", "Perez", ID);

        assertThat(view.hasNationalId()).isTrue();
        assertThat(view.toString()).doesNotContain("1234");
        InMemoryStudents.Stored stored = store.rowsOf(institution).getFirst();
        assertThat(stored.document().encrypted()).isEqualTo("enc[" + ID_NORMALIZED + "]");
        assertThat(stored.document().blindIndex()).isEqualTo(InMemoryStudents.blindIndexOf(institution, ID_NORMALIZED));
        assertThat(view.toString()).doesNotContain(ID).doesNotContain(ID_NORMALIZED);
    }

    @Test
    void theSameDocumentInAnyFormatIsADuplicateInTheInstitution() {
        create(null, "Ana", "Perez", ID);

        for (String variant : List.of(ID, ID_NORMALIZED, "0801-2012-01234 ", "08012012-01234")) {
            assertThatThrownBy(() -> create(null, "Luis", "Lopez", variant))
                    .isInstanceOf(DuplicateNationalIdException.class);
        }
        assertThat(store.rowsOf(institution)).hasSize(1);
    }

    @Test
    void aRefusedDuplicateDocumentLeavesNoEntryAndTheCounterIsGivenBackByTheRollback() {
        create(null, "Ana", "Perez", ID);
        audit.clear();
        transactionCalls.clear();

        assertThatThrownBy(() -> create(null, "Luis", "Lopez", ID)).isInstanceOf(DuplicateNationalIdException.class);

        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void theSameDocumentInAnotherInstitutionIsNotADuplicate() {
        students.create(actorOf(UUID.randomUUID()), requestId, null, "Eva", "Mejia", BIRTH, "F", ID);

        assertThat(create(null, "Ana", "Perez", ID).hasNationalId()).isTrue();
    }

    @Test
    void studentsWithoutDocumentNeverCollideWithEachOther() {
        create(null, "Ana", "Perez", null);
        create(null, "Luis", "Lopez", null);
        create(null, "Eva", "Mejia", "");

        assertThat(store.rowsOf(institution)).hasSize(3);
    }

    @Test
    void aBlankDocumentOnCreationMeansNone() {
        StudentView view = create(null, "Ana", "Perez", "   ");

        assertThat(view.hasNationalId()).isFalse();
        assertThat(store.events).doesNotContain("protect");
    }

    // --- Edit ---

    @Test
    void editingReplacesTheFieldsAndKeepsTheCodeAndTheDocumentWhenTheDocumentIsOmitted() {
        StudentView created = create("MAT-1", "Ana", "Perez", ID);

        StudentView edited = students.update(actor, requestId, created.id(), "Anita", "Perez Lopez",
                LocalDate.of(2011, 1, 2), "M", "INACTIVE", null);

        assertThat(edited.studentCode()).isEqualTo("MAT-1");
        assertThat(edited.firstName()).isEqualTo("Anita");
        assertThat(edited.sex()).isEqualTo("M");
        assertThat(edited.status()).isEqualTo("INACTIVE");
        assertThat(edited.createdAt()).isEqualTo(NOW);
        assertThat(edited.hasNationalId()).isTrue();
        assertThat(store.rowsOf(institution).getFirst().document().encrypted()).isEqualTo("enc[" + ID_NORMALIZED + "]");
    }

    @Test
    void sendingTheNewDocumentReplacesItAndSendingAnEmptyOneRemovesIt() {
        StudentView created = create(null, "Ana", "Perez", ID);

        StudentView replaced = update(created.id(), "Ana", "Perez", "ACTIVE", "AB-99998888");
        assertThat(replaced.hasNationalId()).isTrue();
        assertThat(store.rowsOf(institution).getFirst().document().blindIndex())
                .isEqualTo(InMemoryStudents.blindIndexOf(institution, "AB99998888"));

        StudentView removed = update(created.id(), "Ana", "Perez", "ACTIVE", "");
        assertThat(removed.hasNationalId()).isFalse();
        assertThat(store.rowsOf(institution).getFirst().document()).isNull();

        StudentView removedAgain = update(created.id(), "Ana", "Perez", "ACTIVE", "   ");
        assertThat(removedAgain.hasNationalId()).isFalse();
    }

    @Test
    void aRemovedDocumentCanBeUsedByAnotherStudent() {
        StudentView first = create(null, "Ana", "Perez", ID);
        update(first.id(), "Ana", "Perez", "ACTIVE", "");

        assertThat(create(null, "Luis", "Lopez", ID).hasNationalId()).isTrue();
    }

    @Test
    void resendingTheOwnDocumentInAnotherFormatIsNotADuplicate() {
        StudentView created = create(null, "Ana", "Perez", ID);

        assertThat(update(created.id(), "Ana", "Perez", "ACTIVE", ID_NORMALIZED).hasNationalId()).isTrue();
    }

    @Test
    void movingToTheDocumentOfAnotherStudentIsADuplicateAndChangesNothing() {
        create(null, "Ana", "Perez", ID);
        StudentView second = create(null, "Luis", "Lopez", "ZZ-11112222");
        audit.clear();

        assertThatThrownBy(() -> update(second.id(), "Luis", "Lopez", "ACTIVE", ID_NORMALIZED))
                .isInstanceOf(DuplicateNationalIdException.class);

        assertThat(audit).isEmpty();
        assertThat(store.students.findById(institution, second.id()).orElseThrow().hasNationalId())
                .isTrue();
    }

    @Test
    void anInvalidEditChangesNothingAndWritesNoEntry() {
        StudentView created = create(null, "Ana", "Perez", null);
        audit.clear();

        assertThatThrownBy(() -> update(created.id(), " ", "Perez", "ACTIVE", null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> update(created.id(), "Ana", "Perez", "DELETED", null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> update(created.id(), "Ana", "Perez", "ACTIVE", "bad"))
                .isInstanceOf(InvalidStudentException.class);

        assertThat(audit).isEmpty();
        assertThat(store.students.findById(institution, created.id()).orElseThrow().firstName()).isEqualTo("Ana");
    }

    @Test
    void aStudentThatDoesNotExistIsNotFoundAndSoIsOneOfAnotherInstitution() {
        StudentView mine = create(null, "Ana", "Perez", null);

        assertThatThrownBy(() -> update(UUID.randomUUID(), "Ana", "Perez", "ACTIVE", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> students.update(actorOf(UUID.randomUUID()), requestId, mine.id(), "X", "Y", BIRTH,
                "F", "ACTIVE", null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> students.get(actorOf(UUID.randomUUID()), requestId, mine.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> students.get(actor, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void gettingAStudentReturnsItsViewInAReadOnlyTransaction() {
        StudentView created = create(null, "Ana", "Perez", ID);
        readOnlyFlags.clear();

        StudentView found = students.get(actor, requestId, created.id());

        assertThat(found).isEqualTo(created);
        assertThat(readOnlyFlags).contains("true");
    }

    @Test
    void deactivatingAndListingAgainShowsTheInactiveStudentAndTheFilterTellsThemApart() {
        StudentView ana = create(null, "Ana", "Perez", null);
        create(null, "Luis", "Lopez", null);
        update(ana.id(), "Ana", "Perez", "INACTIVE", null);

        assertThat(students.list(actor, requestId, 0, 50, null, null, null).total()).isEqualTo(2);
        assertThat(students.list(actor, requestId, 0, 50, null, null, "INACTIVE").items())
                .extracting(StudentView::firstName).containsExactly("Ana");
        assertThat(students.list(actor, requestId, 0, 50, null, null, "ACTIVE").items())
                .extracting(StudentView::firstName).containsExactly("Luis");
        assertThat(store.rowsOf(institution)).as("nothing is deleted").hasSize(2);
    }

    // --- Audit ---

    @Test
    void creatingWritesOneEntryWithTheStateAfterAndNothingBefore() {
        StudentView created = create("MAT-1", "Ana", "Perez", ID);

        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("students.student.created");
            assertThat(entry.entityType()).isEqualTo("students.student");
            assertThat(entry.entityId()).isEqualTo(created.id().toString());
            assertThat(entry.institutionId()).isEqualTo(institutionUuid);
            assertThat(entry.actorId()).isEqualTo(actor.accountId());
            assertThat(entry.beforeValue()).isNull();
            JsonNode after = json(entry.afterValue());
            assertThat(after.get("studentCode").asString()).isEqualTo("MAT-1");
            assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(after.get("hasNationalId").asBoolean()).isTrue();
            assertThat(fieldNames(after)).containsExactlyInAnyOrder("studentCode", "status", "hasNationalId");
        });
        assertThat(committed()).isTrue();
    }

    @Test
    void anAuditEntryWithoutADocumentSaysSo() {
        create(null, "Ana", "Perez", null);

        JsonNode after = json(audit.getFirst().afterValue());
        assertThat(after.get("hasNationalId").asBoolean()).isFalse();
    }

    @Test
    void theAuditTrailNeverHoldsTheNamesNorTheBirthDateNorAnyTailOfTheDocumentOfAMinor() {
        String tailed = "0801-2012-0Q97X";
        StudentView created = create("MAT-9", "Zuleyma", "Quintanilla", tailed);
        students.update(actor, requestId, created.id(), "Zuleymita", "Quintanilla Mejia", LocalDate.of(2011, 2, 3),
                "M", "INACTIVE", "ZZ-5555Q97X");
        students.update(actor, requestId, created.id(), "Zuleymita", "Quintanilla Mejia", LocalDate.of(2011, 2, 3),
                "M", "INACTIVE", "");

        assertThat(audit).hasSize(3);
        for (AuditEntry entry : audit) {
            String values = entry.beforeValue() + " " + entry.afterValue();
            assertThat(values).doesNotContain("Zuleyma").doesNotContain("Zuleymita").doesNotContain("Quintanilla")
                    .doesNotContain("2012-05-20").doesNotContain("2011-02-03").doesNotContain("2012")
                    .doesNotContain("Q97X").doesNotContain("97X").doesNotContain("7X").doesNotContain("0801");
        }
    }

    @Test
    void anUpdateEntryNamesTheFieldsThatChangedButNeverTheirValues() {
        StudentView created = create("MAT-9", "Ana", "Perez", null);
        audit.clear();

        students.update(actor, requestId, created.id(), "Anita", "Perez", LocalDate.of(2011, 2, 3), "F", "ACTIVE",
                "AB-99998888");
        students.update(actor, requestId, created.id(), "Anita", "Perez", LocalDate.of(2011, 2, 3), "F", "ACTIVE",
                null);

        assertThat(json(audit.get(0).afterValue()).get("changedFields").asString())
                .isEqualTo("firstName,birthDate,nationalId");
        assertThat(json(audit.get(0).beforeValue()).has("changedFields")).isFalse();
        assertThat(json(audit.get(1).afterValue()).get("changedFields").asString()).isEmpty();
        assertThat(json(audit.get(1).afterValue()).get("studentCode").asString()).isEqualTo("MAT-9");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    @Test
    void editingWritesOneEntryWithTheStateBeforeAndAfter() {
        StudentView created = create(null, "Ana", "Perez", ID);
        audit.clear();

        update(created.id(), "Anita", "Perez", "INACTIVE", "");

        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("students.student.updated");
            JsonNode before = json(entry.beforeValue());
            JsonNode after = json(entry.afterValue());
            assertThat(before.has("firstName")).isFalse();
            assertThat(before.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(before.get("hasNationalId").asBoolean()).isTrue();
            assertThat(after.get("changedFields").asString()).isEqualTo("firstName,status,nationalId");
            assertThat(after.get("status").asString()).isEqualTo("INACTIVE");
            assertThat(after.get("hasNationalId").asBoolean()).isFalse();
        });
    }

    @Test
    void noAuditEntryEverCarriesTheDocumentItsCiphertextOrItsBlindIndex() {
        StudentView created = create(null, "Ana", "Perez", ID);
        update(created.id(), "Ana", "Perez", "ACTIVE", "AB-99998888");
        update(created.id(), "Ana", "Perez", "ACTIVE", ID);
        update(created.id(), "Ana", "Perez", "ACTIVE", "");

        assertThat(audit).hasSize(4);
        for (AuditEntry entry : audit) {
            String everything = entry + " " + entry.beforeValue() + " " + entry.afterValue();
            assertThat(everything).doesNotContain(ID).doesNotContain(ID_NORMALIZED).doesNotContain("AB99998888")
                    .doesNotContain("AB-99998888").doesNotContain("enc[").doesNotContain("bi:");
        }
    }

    // --- Lists ---

    @Test
    void theDefaultListIsThe50RowsOfPageZeroSortedByLastNameAscending() {
        create(null, "Ana", "Zelaya", null);
        create(null, "Luis", "Aguilar", null);

        Page<StudentView> page = students.list(actor, requestId, 0, 50, null, null, null);

        assertThat(page.items()).extracting(StudentView::lastName).containsExactly("Aguilar", "Zelaya");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        assertThat(store.lastRequest.sortField()).isEqualTo("lastName");
        assertThat(store.lastRequest.ascending()).isTrue();
        assertThat(readOnlyFlags).contains("true");
    }

    @Test
    void theAllowedSortFieldsAreTheFourOfTheNoteWithEitherDirection() {
        create(null, "Ana", "Zelaya", null);
        create(null, "Luis", "Aguilar", null);

        for (String field : List.of("studentCode", "lastName", "firstName", "birthDate")) {
            students.list(actor, requestId, 0, 50, field, null, null);
            assertThat(store.lastRequest.sortField()).isEqualTo(field);
            assertThat(store.lastRequest.ascending()).isTrue();
            students.list(actor, requestId, 0, 50, field + ",desc", null, null);
            assertThat(store.lastRequest.ascending()).isFalse();
            students.list(actor, requestId, 0, 50, field + ",ASC", null, null);
            assertThat(store.lastRequest.ascending()).isTrue();
        }
        assertThat(students.list(actor, requestId, 0, 50, "studentCode,desc", null, null).items())
                .extracting(StudentView::studentCode).containsExactly("E-000002", "E-000001");
    }

    @Test
    void aSortFieldOutsideTheSetOrAMalformedDirectionIsRefused() {
        for (String sort : List.of("password", "nationalId", "hasNationalId", "status", "id", "lastName,sideways",
                "lastName,asc,desc", ",asc", "createdAt")) {
            assertThatThrownBy(() -> students.list(actor, requestId, 0, 50, sort, null, null))
                    .as(sort).isInstanceOf(InvalidStudentException.class);
        }
    }

    @Test
    void thePageParametersAreBoundedByTheServer() {
        assertThat(students.list(actor, requestId, 0, 200, null, null, null).size()).isEqualTo(200);
        assertThatThrownBy(() -> students.list(actor, requestId, 0, 201, null, null, null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.list(actor, requestId, 0, 0, null, null, null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.list(actor, requestId, -1, 50, null, null, null))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void theSearchNeedsAtLeastTwoCharactersOnceStrippedAndABlankOneIsNoSearch() {
        create(null, "Ana", "Perez", null);
        create(null, "Luis", "Lopez", null);

        assertThatThrownBy(() -> students.list(actor, requestId, 0, 50, null, "a", null))
                .isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> students.list(actor, requestId, 0, 50, null, "  a ", null))
                .isInstanceOf(InvalidStudentException.class);
        assertThat(students.list(actor, requestId, 0, 50, null, "per", null).items())
                .extracting(StudentView::lastName).containsExactly("Perez");
        assertThat(students.list(actor, requestId, 0, 50, null, "  per  ", null).total()).isEqualTo(1);
        assertThat(students.list(actor, requestId, 0, 50, null, "   ", null).total()).isEqualTo(2);
        assertThat(store.lastFilter.text()).isNull();
        assertThat(students.list(actor, requestId, 0, 50, null, "e-000002", null).items())
                .extracting(StudentView::firstName).containsExactly("Luis");
    }

    @Test
    void anUnknownStatusFilterIsRefused() {
        assertThatThrownBy(() -> students.list(actor, requestId, 0, 50, null, null, "DELETED"))
                .isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void aListOnlyEverShowsTheStudentsOfTheInstitutionOfTheActor() {
        create(null, "Ana", "Perez", null);
        students.create(actorOf(UUID.randomUUID()), requestId, null, "Eva", "Mejia", BIRTH, "F", null);

        Page<StudentView> page = students.list(actor, requestId, 0, 50, null, null, null);

        assertThat(page.items()).extracting(StudentView::firstName).containsExactly("Ana");
    }

    @Test
    void aListInheritsNoStudentFromAnotherInstitutionEvenByCode() {
        students.create(actorOf(UUID.randomUUID()), requestId, "SECRET-1", "Eva", "Mejia", BIRTH, "F", null);

        assertThat(students.list(actor, requestId, 0, 50, null, "secret", null).items()).isEmpty();
    }
}
