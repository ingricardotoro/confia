package com.confia.guardians.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.application.importing.CreateOutcome;
import com.confia.guardians.application.importing.GuardianImportRow;
import com.confia.guardians.application.importing.LinkImportRow;
import com.confia.guardians.application.importing.LinkOutcome;
import com.confia.guardians.application.importing.PartialImportException;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.InvalidGuardianException;
import com.confia.guardians.domain.PreferredChannel;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentRef;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two ports {@code guardians} publishes to {@code imports}, over the in-memory registry: the judgment of the rows
 * (and that it never looks at the stored documents), the registration through the same use cases as the hand
 * registration, the blocks that fall back row by row, the idempotence by reference, and the sets of links of a student
 * that are registered once and never replace links that exist. No cell reaches the audit trail.
 */
class GuardianImportTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryGuardians world = new InMemoryGuardians();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls, readOnlyFlags);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institutionUuid,
            UUID.randomUUID(), UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    private final String requestId = UUID.randomUUID().toString();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ManageGuardians manage = new ManageGuardians(runner, world.guardians, world.links, world.students,
            world.protector, auditWriter, clock);
    private final ManageGuardianLinks manageLinks = new ManageGuardianLinks(runner, world.guardians, world.links,
            world.students, auditWriter, clock);
    private final GuardianImport guardians = new GuardianImport(runner, world.guardians, manage);
    private final GuardianLinkImport links = new GuardianLinkImport(runner, world.guardians, world.links,
            world.students, manageLinks);

    private static GuardianImportRow row(int line, String ref, String first, String last, String document) {
        return new GuardianImportRow(line, ref, first, last, document, null, null, null, null, null);
    }

    // --- guardians: judging ---

    @Test
    void aValidRowHasNoProblemAndAnInvalidOneNamesTheFieldAndTheReason() {
        List<RowProblem> problems = guardians.evaluate(actor, requestId, List.of(
                row(2, "P1", "Rosa", "Aguilar", "0801-1980-12345"),
                new GuardianImportRow(3, "P2", "Luis", "Alvarado", null, "12", "mal", null, null, "PIGEON")));

        assertThat(problems).containsExactly(new RowProblem(3, "rtn", "invalid-format"),
                new RowProblem(3, "email", "invalid-format"), new RowProblem(3, "preferredChannel", "invalid-format"));
        assertThat(world.guardiansOf(institution)).isEmpty();
    }

    @Test
    void aRepeatedReferenceOrDocumentInTheFileIsReportedOnTheLaterRowWhateverTheHyphens() {
        List<RowProblem> problems = guardians.evaluate(actor, requestId, List.of(
                row(2, "P1", "Rosa", "Aguilar", "0801-1980-12345"), row(3, "P1", "Luis", "Alvarado", null),
                row(4, "P3", "Eva", "Mejia", "080119801 2345".replace(" ", "")),
                row(5, "P4", "Ana", "Lopez", "0801-1980-12345")));

        assertThat(problems).containsExactly(new RowProblem(3, "legacyId", "duplicate-in-file"),
                new RowProblem(4, "nationalId", "duplicate-in-file"),
                new RowProblem(5, "nationalId", "duplicate-in-file"));
    }

    @Test
    void aReferenceTheInstitutionAlreadyHasIsAlreadyImportedButAStoredDocumentIsNeverLookedUp() {
        guardians.createAll(actor, requestId, List.of(row(2, "P1", "Rosa", "Aguilar", "0801-1980-12345")));

        List<RowProblem> problems = guardians.evaluate(actor, requestId, List.of(
                row(2, "P1", "Rosa", "Aguilar", null), row(3, "P2", "Luis", "Alvarado", "0801-1980-12345")));

        assertThat(problems).containsExactly(new RowProblem(2, "legacyId", "already-imported"));
    }

    @Test
    void aReferenceOfAnotherInstitutionDoesNotCount() {
        guardians.createAll(actor, requestId, List.of(row(2, "P1", "Rosa", "Aguilar", null)));
        AuthenticatedActor other = new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));

        assertThat(guardians.evaluate(other, requestId, List.of(row(2, "P1", "Rosa", "Aguilar", null)))).isEmpty();
    }

    // --- guardians: registering ---

    @Test
    void theRowsAreRegisteredWithTheCodeTheProtectedDocumentAndTheAuditOfAHandRegistration() {
        List<CreateOutcome> outcomes = guardians.createAll(actor, requestId, List.of(
                new GuardianImportRow(2, " P1 ", "Rosa", "Aguilar", "0801-1980-12345", "0801-1980-123456",
                        "rosa@example.org", "9999-8888", "Casa", "EMAIL"),
                row(3, "P2", "Luis", "Alvarado", null)));

        assertThat(outcomes).containsExactly(CreateOutcome.CREATED, CreateOutcome.CREATED);
        List<InMemoryGuardians.Stored> stored = world.guardiansOf(institution);
        assertThat(stored).extracting(s -> s.guardian().guardianCode()).containsExactly("P-000001", "P-000002");
        assertThat(stored.getFirst().document()).isNotNull();
        assertThat(stored.getFirst().rtn()).isNotNull();
        assertThat(stored.getFirst().guardian().preferredChannel()).isEqualTo(PreferredChannel.EMAIL);
        assertThat(world.guardianRefs).containsKey(InMemoryGuardians.refKey(institution, "P1"));
        assertThat(audit).extracting(AuditEntry::action).containsExactly("guardians.guardian.created",
                "guardians.guardian.created");
        assertThat(audit.toString()).doesNotContain("Rosa", "Aguilar", "0801", "rosa@example.org", "9999", "Casa");
    }

    @Test
    void aBlockWithAReferenceTakenInBetweenFallsBackRowByRowAndTheOthersAreCreated() {
        guardians.createAll(actor, requestId, List.of(row(2, "P2", "Previo", "Previo", null)));

        List<CreateOutcome> outcomes = guardians.createAll(actor, requestId, List.of(
                row(2, "P2", "Luis", "Alvarado", null), row(3, "P1", "Rosa", "Aguilar", null),
                row(4, "P3", "Eva", "Mejia", null)));

        assertThat(outcomes).containsExactly(CreateOutcome.ALREADY_IMPORTED, CreateOutcome.CREATED,
                CreateOutcome.CREATED);
        assertThat(world.guardiansOf(institution)).hasSize(3);
    }

    @Test
    void aDocumentHeldByAnotherGuardianIsRejectedOnItsRowAndTheRefusalIsAuditedWithoutValues() {
        manage.create(actor, requestId, "Previo", "Previo", "0801-1980-12345", null, null, null, null, null);
        audit.clear();

        List<CreateOutcome> outcomes = guardians.createAll(actor, requestId, List.of(
                row(2, "P1", "Rosa", "Aguilar", "0801-1980-12345"), row(3, "P2", "Luis", "Alvarado", null)));

        assertThat(outcomes).containsExactly(CreateOutcome.DUPLICATE_NATIONAL_ID, CreateOutcome.CREATED);
        assertThat(audit).extracting(AuditEntry::outcome).contains("denied", "success");
        assertThat(audit.toString()).doesNotContain("0801", "Rosa");
        assertThat(world.guardianRefs).doesNotContainKey(InMemoryGuardians.refKey(institution, "P1"));
    }

    @Test
    void aBlockRunsInOneTransactionOfTheCallerWithoutOpeningAnotherPerRow() {
        transactionCalls.clear();
        guardians.createAll(actor, requestId, List.of(row(2, "P1", "Rosa", "Aguilar", null),
                row(3, "P2", "Luis", "Alvarado", null), row(4, "P3", "Eva", "Mejia", null)));
        assertThat(transactionCalls.stream().filter("commit"::equals)).as("one transaction for the block").hasSize(1);
    }

    @Test
    void moreThanABlockOfRowsAreAllRegistered() {
        List<GuardianImportRow> rows = new ArrayList<>();
        for (int i = 0; i < GuardianImport.BLOCK_SIZE + 5; i++) {
            rows.add(row(i + 2, "P" + i, "Nombre", "Apellido", null));
        }

        assertThat(guardians.createAll(actor, requestId, rows)).hasSize(105).containsOnly(CreateOutcome.CREATED);
    }

    @Test
    void aFailureThatIsNotAConflictAfterSomeWereCommittedIsPartialAndWithoutFailureItIsPlain() {
        List<GuardianImportRow> rows = new ArrayList<>();
        for (int i = 0; i < GuardianImport.BLOCK_SIZE; i++) {
            rows.add(row(i + 2, "P" + i, "Nombre", "Apellido", null));
        }
        rows.add(new GuardianImportRow(500, "BAD", " ", "Apellido", null, null, null, null, null, null));

        assertThatThrownBy(() -> guardians.createAll(actor, requestId, rows))
                .isInstanceOfSatisfying(PartialImportException.class, e -> {
                    assertThat(e.created()).isEqualTo(GuardianImport.BLOCK_SIZE);
                    assertThat(e.failure()).isInstanceOf(InvalidGuardianException.class);
                });
        assertThatThrownBy(() -> guardians.createAll(actor, requestId,
                List.of(new GuardianImportRow(2, "BAD", " ", "Apellido", null, null, null, null, null, null))))
                .isInstanceOf(InvalidGuardianException.class);
    }

    // --- links ---

    private Guardian guardian(String ref, String code, GuardianStatus status) {
        Guardian guardian = new Guardian(UUID.randomUUID(), code, "Nombre", "Apellido", false, false, null, null,
                null, PreferredChannel.NONE, status, NOW, NOW);
        world.arrange(institution, guardian);
        world.guardianRefs.put(InMemoryGuardians.refKey(institution, ref), guardian.id());
        return guardian;
    }

    private StudentRef student(String ref, String code, boolean active) {
        StudentRef student = new StudentRef(UUID.randomUUID(), code, "Alumno", "Apellido", active);
        world.arrange(institution, student);
        world.studentRefs.put(InMemoryGuardians.refKey(institution, ref), student.studentId());
        return student;
    }

    private static LinkImportRow link(int line, String student, String guardian, String percent, String primary) {
        return new LinkImportRow(line, student, guardian, "MOTHER", percent, primary, null);
    }

    @Test
    void aValidGroupIsJudgedWithoutProblemsAndAppliedWithTheSameUseCaseAsTheSetOfTheStudent() {
        StudentRef s1 = student("S1", "E-000001", true);
        Guardian g1 = guardian("G1", "P-000001", GuardianStatus.ACTIVE);
        guardian("G2", "P-000002", GuardianStatus.ACTIVE);
        List<LinkImportRow> rows = List.of(link(2, "S1", "G1", "60.00", "true"), link(3, "S1", "G2", "40", ""));

        assertThat(links.evaluate(actor, requestId, rows)).isEmpty();
        assertThat(links.applyAll(actor, requestId, rows)).containsExactly(LinkOutcome.CREATED,
                LinkOutcome.CREATED);

        assertThat(world.links.currentOfStudent(institution, s1.studentId())).hasSize(2);
        assertThat(world.links.currentOfStudent(institution, s1.studentId()))
                .anyMatch(l -> l.guardianId().equals(g1.id()) && l.primaryPayer());
        assertThat(world.events).containsSubsequence("lock-student", "read-links", "lock-guardians-shared",
                "insert-link");
        assertThat(audit).extracting(AuditEntry::action).containsExactly("guardians.links.replaced");
        assertThat(audit.toString()).doesNotContain("Alumno", "Apellido", "Nombre");
    }

    @Test
    void thirdsInCentsAreAcceptedAndNinetyNineNinetyNineIsRefusedByTheSameRuleAsTheHandSet() {
        student("S1", "E-000001", true);
        student("S2", "E-000002", true);
        for (int i = 1; i <= 3; i++) {
            guardian("G" + i, "P-00000" + i, GuardianStatus.ACTIVE);
        }

        assertThat(links.evaluate(actor, requestId, List.of(link(2, "S1", "G1", "33.33", ""),
                link(3, "S1", "G2", "33.33", ""), link(4, "S1", "G3", "33.34", "")))).isEmpty();
        assertThat(links.evaluate(actor, requestId, List.of(link(2, "S2", "G1", "33.33", ""),
                link(3, "S2", "G2", "33.33", ""), link(4, "S2", "G3", "33.33", "")))).extracting(RowProblem::reason)
                .containsOnly("sum-not-100");
    }

    @Test
    void aStudentThatAlreadyHasLinksIsReportedAndNeverReplacedNorWrittenOnApplication() {
        StudentRef s1 = student("S1", "E-000001", true);
        guardian("G1", "P-000001", GuardianStatus.ACTIVE);
        List<LinkImportRow> rows = List.of(link(2, "S1", "G1", "100", ""));
        links.applyAll(actor, requestId, rows);
        int writes = audit.size();

        assertThat(links.evaluate(actor, requestId, rows)).containsExactly(
                new RowProblem(2, "studentLegacyId", "student-already-linked"));
        assertThat(links.applyAll(actor, requestId, rows)).containsExactly(LinkOutcome.ALREADY_LINKED);
        assertThat(world.links.historyOfStudent(institution, s1.studentId())).hasSize(1);
        assertThat(audit).hasSize(writes);
    }

    @Test
    void aStudentOrGuardianThatBecameInactiveAfterTheJudgmentIsReportedPerSet() {
        StudentRef s1 = student("S1", "E-000001", true);
        student("S2", "E-000002", true);
        Guardian g1 = guardian("G1", "P-000001", GuardianStatus.ACTIVE);
        List<LinkImportRow> rows = List.of(link(2, "S1", "G1", "100", ""), link(3, "S2", "G1", "100", ""));
        world.arrange(institution, new StudentRef(s1.studentId(), s1.studentCode(), "Alumno", "Apellido", false));
        assertThat(links.applyAll(actor, requestId, rows)).containsExactly(LinkOutcome.STUDENT_NOT_ACTIVE,
                LinkOutcome.CREATED);

        world.arrange(institution, new Guardian(g1.id(), g1.guardianCode(), "Nombre", "Apellido", false, false, null,
                null, null, PreferredChannel.NONE, GuardianStatus.INACTIVE, NOW, NOW));
        student("S3", "E-000003", true);
        assertThat(links.applyAll(actor, requestId, List.of(link(2, "S3", "G1", "100", ""))))
                .containsExactly(LinkOutcome.GUARDIAN_NOT_ACTIVE);
    }

    @Test
    void aFailureThatIsNotARefusalAfterSomeSetsWereCommittedIsPartialAndBeforeThemIsPlain() {
        student("S1", "E-000001", true);
        guardian("G1", "P-000001", GuardianStatus.ACTIVE);
        // S2 passes as an arranged row but its guardian disappears from the registry between judging and applying.
        student("S2", "E-000002", true);
        guardian("G2", "P-000002", GuardianStatus.ACTIVE);
        world.guardianRefs.put(InMemoryGuardians.refKey(institution, "G9"), UUID.randomUUID());
        List<LinkImportRow> rows = List.of(link(2, "S1", "G1", "100", ""), link(3, "S2", "G9", "100", ""));

        assertThatThrownBy(() -> links.applyAll(actor, requestId, rows))
                .isInstanceOfSatisfying(PartialImportException.class, e -> {
                    assertThat(e.created()).isEqualTo(1);
                    assertThat(e.failure()).isInstanceOf(IllegalStateException.class);
                });
        List<LinkImportRow> onlyTheBroken = List.of(link(3, "S2", "G9", "100", ""));
        assertThatThrownBy(() -> links.applyAll(actor, requestId, onlyTheBroken))
                .isInstanceOf(IllegalStateException.class);
    }
}
