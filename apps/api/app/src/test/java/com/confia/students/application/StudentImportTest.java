package com.confia.students.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.CreateOutcome;
import com.confia.students.application.importing.ImportRow;
import com.confia.students.application.importing.PartialImportException;
import com.confia.students.application.importing.RowProblem;
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
 * The bulk import of the registry over in-memory ports: every reason of the cut, in the order of the lines, and the
 * creation through the same use case as the hand registration (generated code, encrypted document with its blind
 * index, audit). The rollback of a failed block is the database's; the concurrent case is proven against PostgreSQL in
 * {@code ImportEndpointsIT}.
 */
class StudentImportTest {

    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryStudents store = new InMemoryStudents();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = actorOf(institutionUuid);
    private final String requestId = UUID.randomUUID().toString();
    private final ManageStudents manage = new ManageStudents(runner, store.students, store.protector, auditWriter, clock);
    private final StudentImport imports = new StudentImport(runner, store.students, store.protector, manage, clock);

    private static AuthenticatedActor actorOf(UUID institutionId) {
        return new AuthenticatedActor(UUID.randomUUID(), institutionId, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private static ImportRow row(int line, String ref, String first, String last, String birth, String sex,
            String id) {
        return new ImportRow(line, ref, first, last, birth, sex, id);
    }

    private static ImportRow ok(int line, String ref) {
        return row(line, ref, "Ana", "Perez", "2012-05-20", "F", null);
    }

    private List<RowProblem> evaluate(ImportRow... rows) {
        return imports.evaluate(actor, requestId, List.of(rows));
    }

    private List<CreateOutcome> create(ImportRow... rows) {
        return imports.createAll(actor, requestId, List.of(rows));
    }

    // --- evaluate ---

    @Test
    void validRowsHaveNoProblemsAndNothingIsWritten() {
        assertThat(evaluate(ok(2, "L1"), row(3, "L2", "Luis", "Lopez", "2011-01-02", "M", "0801-2012-01234")))
                .isEmpty();

        assertThat(store.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(store.events).doesNotContain("sequence", "protect", "insert-imported");
    }

    @Test
    void theFieldProblemsComeWithTheirLineAndColumnInLineOrder() {
        List<RowProblem> problems = evaluate(row(2, "L1", "", "Perez", "2012-05-20", "F", null),
                ok(3, "L2"), row(4, "L3", "Ana", "Perez", "2099-01-01", "F", null),
                row(5, "L4", "Ana", "Perez", "2012-02-30", "Q", "12"));

        assertThat(problems).containsExactly(new RowProblem(2, "firstName", "required"),
                new RowProblem(4, "birthDate", "future-birth-date"), new RowProblem(5, "birthDate", "invalid-date"),
                new RowProblem(5, "sex", "invalid-format"), new RowProblem(5, "nationalId", "invalid-format"));
    }

    @Test
    void aRepeatedReferenceIsDuplicateInFileOnTheLaterRowOnly() {
        assertThat(evaluate(ok(2, "L1"), ok(3, "L2"), ok(4, " L1 "), ok(5, "L1"))).containsExactly(
                new RowProblem(4, "legacyId", "duplicate-in-file"), new RowProblem(5, "legacyId", "duplicate-in-file"));
    }

    @Test
    void aRepeatedDocumentIsDuplicateInFileEvenWithAnotherSpellingOfTheHyphens() {
        List<RowProblem> problems = evaluate(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234"),
                row(3, "L2", "Luis", "Lopez", "2012-05-20", "M", "0801201201234"),
                row(4, "L3", "Eva", "Mejia", "2012-05-20", "F", "0801-2012-01234"),
                row(5, "L4", "Rosa", "Diaz", "2012-05-20", "F", "0801-2012-99999"));

        assertThat(problems).containsExactly(new RowProblem(3, "nationalId", "duplicate-in-file"),
                new RowProblem(4, "nationalId", "duplicate-in-file"));
    }

    @Test
    void aReferenceTheInstitutionAlreadyHasIsAlreadyImportedAndItsDocumentIsNotCompared() {
        create(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234"));

        List<RowProblem> problems = evaluate(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234"),
                ok(3, "L2"));

        assertThat(problems).containsExactly(new RowProblem(2, "legacyId", "already-imported"));
    }

    @Test
    void aDocumentAnotherStudentHoldsIsDuplicateNationalIdWhatEverItsSpelling() {
        manage.create(actor, requestId, null, "Eva", "Mejia", LocalDate.of(2010, 1, 1), "F", "0801-2012-01234");

        List<RowProblem> problems = evaluate(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801201201234"),
                row(3, "L2", "Luis", "Lopez", "2012-05-20", "M", "0801-2012-77777"));

        assertThat(problems).containsExactly(new RowProblem(2, "nationalId", "duplicate-national-id"));
    }

    @Test
    void theStudentsOfAnotherInstitutionAreInvisible() {
        AuthenticatedActor other = actorOf(UUID.randomUUID());
        manage.create(other, requestId, null, "Eva", "Mejia", LocalDate.of(2010, 1, 1), "F", "0801-2012-01234");
        imports.createAll(other, requestId, List.of(ok(2, "L1")));

        assertThat(evaluate(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234"))).isEmpty();
    }

    @Test
    void aRowWithABadReferenceStillHasItsDocumentCheckedAndAProblemInOneFieldDoesNotHideAnother() {
        manage.create(actor, requestId, null, "Eva", "Mejia", LocalDate.of(2010, 1, 1), "F", "0801-2012-01234");

        assertThat(evaluate(row(2, "", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234")))
                .containsExactly(new RowProblem(2, "legacyId", "required"),
                        new RowProblem(2, "nationalId", "duplicate-national-id"));
    }

    @Test
    void anEmptyListHasNoProblemsAndTheLookupsAreInChunks() {
        assertThat(imports.evaluate(actor, requestId, List.of())).isEmpty();

        List<ImportRow> many = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            many.add(ok(i + 2, "L" + i));
        }
        assertThat(imports.evaluate(actor, requestId, many)).isEmpty();
        assertThat(store.events.stream().filter("existing-refs"::equals).count()).as("1000 references per read")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void theProblemsNeverCarryAValue() {
        List<RowProblem> problems = evaluate(row(2, "SECRETO-REF", "", "Apellido-Secreto", "2099-01-01", "Z", "SECRETO1"));

        assertThat(problems.toString()).doesNotContain("SECRETO").doesNotContain("Apellido").doesNotContain("2099");
    }

    // --- createAll ---

    @Test
    void theRowsAreRegisteredThroughTheUseCaseOfTheRegistryWithTheirReference() {
        List<CreateOutcome> outcomes = create(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234"),
                row(3, "L2", "Luis", "Lopez", "2011-01-02", "", null));

        assertThat(outcomes).containsExactly(CreateOutcome.CREATED, CreateOutcome.CREATED);
        assertThat(store.rowsOf(institution)).extracting(s -> s.student().studentCode())
                .containsExactly("E-000001", "E-000002");
        assertThat(store.rowsOf(institution)).extracting(s -> s.student().sex().name())
                .containsExactly("F", "UNSPECIFIED");
        assertThat(store.externalRefs.values()).containsExactly("L1", "L2");
        assertThat(store.rowsOf(institution).getFirst().document().encrypted()).startsWith("enc[");
        assertThat(store.events).contains("protect", "sequence");
        assertThat(audit).extracting(AuditEntry::action).containsExactly("students.student.created",
                "students.student.created");
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    @Test
    void theAuditEntriesOfAnImportedStudentAreThoseOfAHandRegistration() {
        create(row(2, "L1-secreto", "Ana", "Apellido-Secreto", "2012-05-20", "F", "0801-2012-01234"));
        manage.create(actor, requestId, null, "Ana", "Apellido-Secreto", LocalDate.of(2012, 5, 20), "F", null);

        assertThat(audit).hasSize(2);
        assertThat(audit.getFirst().afterValue()).isEqualTo(audit.get(1).afterValue()
                .replace("E-000002", "E-000001").replace("\"hasNationalId\":false", "\"hasNationalId\":true"));
        assertThat(audit.getFirst().toString() + audit.getFirst().afterValue())
                .doesNotContain("L1-secreto").doesNotContain("Apellido").doesNotContain("0801");
    }

    @Test
    void aBlockWithAnExistingReferenceIsRetriedRowByRowSoTheOthersAreStillCreated() {
        create(ok(2, "L1"));

        List<CreateOutcome> outcomes = create(ok(2, "L1"), ok(3, "L2"), ok(4, "L3"));

        assertThat(outcomes).containsExactly(CreateOutcome.ALREADY_IMPORTED, CreateOutcome.CREATED,
                CreateOutcome.CREATED);
        assertThat(transactionCalls).as("the failed block was rolled back").contains("rollback");
        assertThat(store.externalRefs.values()).containsExactly("L1", "L2", "L3");
    }

    @Test
    void aDocumentTakenInBetweenIsReportedAsDuplicateNationalIdForThatRowOnly() {
        manage.create(actor, requestId, null, "Eva", "Mejia", LocalDate.of(2010, 1, 1), "F", "0801-2012-01234");

        List<CreateOutcome> outcomes = create(row(2, "L1", "Ana", "Perez", "2012-05-20", "F", "0801201201234"),
                ok(3, "L2"));

        assertThat(outcomes).containsExactly(CreateOutcome.DUPLICATE_NATIONAL_ID, CreateOutcome.CREATED);
    }

    @Test
    void moreRowsThanABlockAreAllRegisteredInOrder() {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < StudentImport.BLOCK_SIZE * 2 + 7; i++) {
            rows.add(ok(i + 2, "L" + i));
        }

        List<CreateOutcome> outcomes = imports.createAll(actor, requestId, rows);

        assertThat(outcomes).hasSize(rows.size()).containsOnly(CreateOutcome.CREATED);
        assertThat(store.rowsOf(institution)).hasSize(rows.size());
        assertThat(transactionCalls.stream().filter("commit"::equals).count()).as("one commit per block").isEqualTo(3);
    }

    @Test
    void aRowThatWasNotEvaluatedAndBreaksARuleIsRefusedAndNothingOfItIsKept() {
        org.junit.jupiter.api.Assertions.assertThrows(com.confia.students.domain.InvalidStudentException.class,
                () -> create(row(2, "L1", "", "Perez", "2012-05-20", "F", null)));

        assertThat(store.rowsOf(institution)).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void noRowsRegisterNothing() {
        assertThat(imports.createAll(actor, requestId, List.of())).isEmpty();
        assertThat(transactionCalls).isEmpty();
    }

    @Test
    void anUnexpectedFailureAfterSomeBlocksCommittedReportsHowManyWereCreatedAndKeepsTheCause() {
        List<ImportRow> rows = new ArrayList<>();
        for (int i = 0; i < StudentImport.BLOCK_SIZE; i++) {
            rows.add(ok(i + 2, "L" + i));
        }
        // An unevaluated row that breaks a rule fails the second block with an error that is not a conflict.
        rows.add(row(StudentImport.BLOCK_SIZE + 2, "BAD", "", "Perez", "2012-05-20", "F", null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> imports.createAll(actor, requestId, rows))
                .isInstanceOfSatisfying(PartialImportException.class, e -> {
                    assertThat(e.created()).isEqualTo(StudentImport.BLOCK_SIZE);
                    assertThat(e.failure()).isInstanceOf(com.confia.students.domain.InvalidStudentException.class);
                    assertThat(e.getMessage()).doesNotContain("BAD", "Perez");
                });
        assertThat(store.rowsOf(institution)).as("the first block stays").hasSize(StudentImport.BLOCK_SIZE);
    }
}
