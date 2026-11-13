package com.confia.imports.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.application.importing.LinkOutcome;
import com.confia.imports.domain.ImportBatch;
import com.confia.imports.domain.ImportFileChangedException;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.ImportNotSimulatedException;

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
import com.confia.students.application.importing.StudentImportGateway;
import com.confia.support.JdbcStubTransactions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The use case of the student import over in-memory ports: what a simulation records, what an application needs and
 * does, the limits and the shape of the file, the counts that always add up, and that the batch, the audit entry and
 * every failure carry no value of the file. The rules of each value belong to {@code students} and are tested there.
 */
class ManageImportsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");
    private static final Set<ImportKind> ALL = java.util.EnumSet.allOf(ImportKind.class);
    private static final String HEADER = "legacyId,firstName,lastName,birthDate\n";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final InMemoryBatches batches = new InMemoryBatches();
    private final FakeStudents students = new FakeStudents();
    private final TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final AuthenticatedActor actor = actorOf(institutionUuid, UUID.randomUUID());
    private final String requestId = UUID.randomUUID().toString();
    private final FakeGuardianGateways.Guardians guardianPort = new FakeGuardianGateways.Guardians();
    private final FakeGuardianGateways.Links linkPort = new FakeGuardianGateways.Links();
    private final ManageImports imports = new ManageImports(runner, batches, students, guardianPort,
            linkPort, auditWriter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static AuthenticatedActor actorOf(UUID institution, UUID account) {
        return new AuthenticatedActor(account, institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private static byte[] file(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A file of {@code count} rows with the references R1... */
    private static byte[] rows(int count) {
        StringBuilder text = new StringBuilder(HEADER);
        for (int i = 1; i <= count; i++) {
            text.append('R').append(i).append(",Nombre").append(i).append(",Apellido").append(i)
                    .append(",2012-05-20\n");
        }
        return file(text.toString());
    }

    private ImportRun simulate(byte[] file) {
        return imports.simulate(ImportKind.STUDENTS, actor, requestId, file);
    }

    private ImportRun apply(byte[] file) {
        return imports.apply(ImportKind.STUDENTS, actor, requestId, file);
    }

    private static List<RejectedImportFileException.FileProblem> refusal(Runnable action) {
        try {
            action.run();
        } catch (RejectedImportFileException e) {
            return e.problems();
        }
        throw new AssertionError("the file was accepted");
    }

    // --- Simulation ---

    @Test
    void aSimulationRecordsAValidatedBatchWithCountsAndTheFingerprintAndWritesNoStudent() {
        byte[] file = rows(5);
        students.problems.add(new RowProblem(3, "birthDate", "invalid-date"));
        students.problems.add(new RowProblem(5, "legacyId", "already-imported"));

        ImportRun run = simulate(file);

        ImportBatchView batch = run.batch();
        assertThat(batch.status()).isEqualTo("VALIDATED");
        assertThat(batch.kind()).isEqualTo("STUDENTS");
        assertThat(batch.totalRows()).isEqualTo(5);
        assertThat(batch.validRows()).isEqualTo(3);
        assertThat(batch.rejectedRows()).isEqualTo(1);
        assertThat(batch.skippedRows()).isEqualTo(1);
        assertThat(batch.createdRows()).isZero();
        assertThat(batch.appliedAt()).isNull();
        assertThat(batch.fileSha256()).matches("[0-9a-f]{64}")
                .isEqualTo(sha256(file));
        assertThat(run.rejections()).containsExactly(new RejectionView(3, "birthDate", "invalid-date"),
                new RejectionView(5, "legacyId", "already-imported"));
        assertThat(run.rejectionsTruncated()).isFalse();
        assertThat(students.created).isEmpty();
        assertThat(batches.rows).hasSize(1);
        assertThat(transactionCalls).contains("commit").doesNotContain("rollback");
    }

    static String sha256(byte[] file) {
        return java.util.HexFormat.of().formatHex(com.confia.shared.security.Digests.sha256(file));
    }

    @Test
    void theRowsReachTheRegistryRawAndWithTheLineWhereTheyStart() {
        simulate(file("firstName;legacyId;lastName;birthDate\n\"Ana\nMaria\";R1;Perez;2012-05-20\nLuis;R2;Lopez;x\n"));

        assertThat(students.evaluated).containsExactly(
                new ImportRow(2, "R1", "Ana\nMaria", "Perez", "2012-05-20", null, null),
                new ImportRow(4, "R2", "Luis", "Lopez", "x", null, null));
    }

    @Test
    void aByteOrderMarkIsDroppedSoTheFirstColumnIsStillRecognised() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = rows(1);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);

        assertThat(simulate(withBom).batch().totalRows()).isEqualTo(1);
    }

    @Test
    void theSemicolonSeparatorIsDetected() {
        ImportRun run = simulate(file("legacyId;firstName;lastName;birthDate\nR1;Ana;Perez;2012-05-20\n"));

        assertThat(run.batch().validRows()).isEqualTo(1);
        assertThat(students.evaluated.getFirst().firstName()).isEqualTo("Ana");
    }

    @Test
    void aRowWithTheWrongNumberOfCellsIsRejectedAndTheOthersAreStillJudged() {
        ImportRun run = simulate(file(HEADER + "R1,Ana,Perez,2012-05-20\nR2,Luis,Lopez\nR3,Eva,Mejia,2012-05-22\n"));

        assertThat(run.batch().totalRows()).isEqualTo(3);
        assertThat(run.batch().rejectedRows()).isEqualTo(1);
        assertThat(run.batch().validRows()).isEqualTo(2);
        assertThat(run.rejections()).containsExactly(new RejectionView(3, "row", "invalid-format"));
    }

    @Test
    void aRowWithADefectAndAlreadyImportedIsRejectedOnceAndNotSkipped() {
        students.problems.add(new RowProblem(2, "legacyId", "already-imported"));
        students.problems.add(new RowProblem(2, "birthDate", "invalid-date"));

        ImportBatchView batch = simulate(rows(2)).batch();

        assertThat(batch.rejectedRows()).isEqualTo(1);
        assertThat(batch.skippedRows()).isZero();
        assertThat(batch.validRows()).isEqualTo(1);
    }

    @Test
    void aRowWithSeveralDefectsCountsOnceButListsEachOfThem() {
        students.problems.add(new RowProblem(2, "firstName", "required"));
        students.problems.add(new RowProblem(2, "birthDate", "invalid-date"));

        ImportRun run = simulate(rows(1));

        assertThat(run.batch().rejectedRows()).isEqualTo(1);
        assertThat(run.rejections()).hasSize(2);
    }

    @Test
    void theCountsAlwaysAddUpToTheTotal() {
        students.problems.add(new RowProblem(2, "firstName", "required"));
        students.problems.add(new RowProblem(4, "legacyId", "already-imported"));
        students.problems.add(new RowProblem(6, "birthDate", "future-birth-date"));

        ImportBatchView batch = simulate(rows(7)).batch();

        assertThat(batch.totalRows()).isEqualTo(batch.validRows() + batch.rejectedRows() + batch.skippedRows());
        assertThat(batch.rejectedRows()).isEqualTo(2);
        assertThat(batch.skippedRows()).isEqualTo(1);
    }

    @Test
    void defectsAreListedFirstThenTheSkippedRowsAndNoMoreThanTwoHundred() {
        for (int line = 2; line <= 151; line++) {
            students.problems.add(new RowProblem(line, "legacyId", "already-imported"));
        }
        for (int line = 152; line <= 301; line++) {
            students.problems.add(new RowProblem(line, "firstName", "required"));
        }

        ImportRun run = simulate(rows(300));

        assertThat(run.rejections()).hasSize(ManageImports.MAX_REPORTED);
        assertThat(run.rejectionsTruncated()).isTrue();
        assertThat(run.rejections().subList(0, 150)).extracting(RejectionView::reason).containsOnly("required");
        assertThat(run.rejections().subList(150, 200)).extracting(RejectionView::reason)
                .containsOnly("already-imported");
        assertThat(run.rejections().subList(0, 150)).extracting(RejectionView::line).isSorted();
        assertThat(run.batch().rejectedRows()).isEqualTo(150);
        assertThat(run.batch().skippedRows()).isEqualTo(150);
    }

    @Test
    void exactlyTwoHundredRejectionsAreNotMarkedTruncated() {
        for (int line = 2; line <= 201; line++) {
            students.problems.add(new RowProblem(line, "firstName", "required"));
        }

        ImportRun run = simulate(rows(200));

        assertThat(run.rejections()).hasSize(200);
        assertThat(run.rejectionsTruncated()).isFalse();
    }

    @Test
    void aFileOfOnlyDefectsHasNoValidRows() {
        for (int line = 2; line <= 4; line++) {
            students.problems.add(new RowProblem(line, "firstName", "required"));
        }

        ImportBatchView batch = simulate(rows(3)).batch();

        assertThat(batch.validRows()).isZero();
        assertThat(batch.rejectedRows()).isEqualTo(3);
    }

    // --- The file ---

    @Test
    void anEmptyFileAndAFileWithOnlyTheHeaderAreRefused() {
        assertThat(refusal(() -> simulate(new byte[0]))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "empty-file"));
        assertThat(refusal(() -> simulate(file("   \n\n")))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "empty-file"));
        assertThat(refusal(() -> simulate(file(HEADER)))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "no-rows"));
        assertThat(batches.rows).isEmpty();
    }

    @Test
    void theFileMayHaveFiveMegabytesAndNotOneByteMore() {
        byte[] exactly = new byte[ManageImports.MAX_BYTES];
        byte[] start = file(HEADER + "R1,Ana,");
        System.arraycopy(start, 0, exactly, 0, start.length);
        java.util.Arrays.fill(exactly, start.length, exactly.length - 12, (byte) 'x');
        byte[] end = file(",2012-05-20\n");
        System.arraycopy(end, 0, exactly, exactly.length - end.length, end.length);

        assertThat(simulate(exactly).batch().totalRows()).isEqualTo(1);
        assertThat(refusal(() -> simulate(new byte[ManageImports.MAX_BYTES + 1])))
                .containsExactly(new RejectedImportFileException.FileProblem("file", "too-large"));
    }

    @Test
    void tenThousandRowsAreAcceptedAndTenThousandAndOneAreRefused() {
        assertThat(simulate(rows(ManageImports.MAX_ROWS)).batch().totalRows()).isEqualTo(10_000);

        assertThat(refusal(() -> simulate(rows(ManageImports.MAX_ROWS + 1))))
                .containsExactly(new RejectedImportFileException.FileProblem("file", "too-many-rows"));
    }

    @Test
    void aFileThatIsNotUtf8IsRefusedWithoutEchoingIt() {
        byte[] latin1 = (HEADER + "R1,Muñoz,Perez,2012-05-20\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] withNul = file(HEADER + "R1,Ana\0,Perez,2012-05-20\n");

        assertThat(refusal(() -> simulate(latin1))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "invalid-encoding"));
        assertThat(refusal(() -> simulate(withNul))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "invalid-encoding"));
    }

    @Test
    void aWrongHeaderIsRefusedAsAWholeAndNothingIsRecorded() {
        assertThat(refusal(() -> simulate(file("legacyId,firstName,birthDate\nR1,Ana,2012-05-20\n"))))
                .containsExactly(new RejectedImportFileException.FileProblem("lastName", "missing-column"));
        assertThat(refusal(() -> simulate(file(HEADER.strip() + ",apellido2\nR1,Ana,Perez,2012-05-20,x\n"))))
                .containsExactly(new RejectedImportFileException.FileProblem("columns[4]", "unknown-column"));
        assertThat(refusal(() -> simulate(file("legacyId,\"firstName\n")))).containsExactly(
                new RejectedImportFileException.FileProblem("file", "malformed-csv"));
        assertThat(batches.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    // --- Application ---

    @Test
    void aFileThatWasNeverSimulatedIsNotApplied() {
        assertThatThrownBy(() -> apply(rows(3))).isInstanceOf(ImportNotSimulatedException.class);

        assertThat(students.created).isEmpty();
        assertThat(batches.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aFileDifferentFromTheOneSimulatedLastIsFileChanged() {
        simulate(rows(3));

        assertThatThrownBy(() -> apply(rows(4))).isInstanceOf(ImportFileChangedException.class);
        assertThat(students.created).isEmpty();
        assertThat(batches.rows).hasSize(1);
    }

    @Test
    void aSingleByteOfDifferenceIsAnotherFile() {
        simulate(file(HEADER + "R1,Ana,Perez,2012-05-20\n"));

        assertThatThrownBy(() -> apply(file(HEADER + "R1,Ana,Perez,2012-05-21\n")))
                .isInstanceOf(ImportFileChangedException.class);
    }

    @Test
    void afterSimulatingTwoFilesEitherCanBeApplied() {
        byte[] first = rows(2);
        byte[] second = rows(3);
        simulate(first);
        simulate(second);

        assertThat(apply(first).batch().createdRows()).isEqualTo(2);
        assertThat(apply(second).batch().status()).isEqualTo("APPLIED");
    }

    @Test
    void aSimulationOfAnotherAccountOrInstitutionDoesNotCount() {
        byte[] file = rows(2);
        imports.simulate(ImportKind.STUDENTS, actorOf(institutionUuid, UUID.randomUUID()), requestId, file);
        imports.simulate(ImportKind.STUDENTS, actorOf(UUID.randomUUID(), actor.accountId()), requestId, file);

        assertThatThrownBy(() -> apply(file)).isInstanceOf(ImportNotSimulatedException.class);
    }

    @Test
    void applyingTheSimulatedFileCreatesTheValidRowsAndRecordsAnAppliedBatchThatPointsToTheSimulation() {
        byte[] file = rows(5);
        students.problems.add(new RowProblem(3, "birthDate", "invalid-date"));
        UUID simulation = simulate(file).batch().id();

        ImportRun run = apply(file);

        assertThat(students.created).extracting(ImportRow::legacyId).containsExactly("R1", "R3", "R4", "R5")
                .as("only the valid rows, in file order").hasSize(4);
        ImportBatchView batch = run.batch();
        assertThat(batch.status()).isEqualTo("APPLIED");
        assertThat(batch.createdRows()).isEqualTo(4).isEqualTo(batch.validRows());
        assertThat(batch.rejectedRows()).isEqualTo(1);
        assertThat(batch.skippedRows()).isZero();
        assertThat(batch.appliedAt()).isEqualTo(NOW);
        assertThat(run.rejections()).containsExactly(new RejectionView(3, "birthDate", "invalid-date"));
        assertThat(batches.rows).hasSize(2);
        assertThat(batches.rows.getLast().simulationId()).isEqualTo(simulation);
    }

    @Test
    void theApplicationJudgesTheFileAgainSoWhatChangedSinceTheSimulationIsNotTrusted() {
        byte[] file = rows(3);
        simulate(file);
        students.problems.add(new RowProblem(2, "legacyId", "already-imported"));
        students.problems.add(new RowProblem(4, "birthDate", "invalid-date"));

        ImportBatchView batch = apply(file).batch();

        assertThat(students.created).extracting(ImportRow::legacyId).containsExactly("R2");
        assertThat(batch.createdRows()).isEqualTo(1);
        assertThat(batch.skippedRows()).isEqualTo(1);
        assertThat(batch.rejectedRows()).isEqualTo(1);
    }

    @Test
    void theApplicationCanRejectMoreRowsThanTheSimulationBecauseOnlyItFindsStoredDocuments() {
        byte[] file = rows(4);
        ImportBatchView simulated = simulate(file).batch();
        students.outcomes = row -> row.legacyId().equals("R3") ? CreateOutcome.DUPLICATE_NATIONAL_ID
                : CreateOutcome.CREATED;

        ImportRun run = apply(file);

        assertThat(simulated.rejectedRows()).isZero();
        assertThat(run.batch().rejectedRows()).isEqualTo(1);
        assertThat(run.batch().createdRows()).isEqualTo(3);
        assertThat(run.batch().totalRows()).isEqualTo(4);
        assertThat(run.rejections()).containsExactly(new RejectionView(4, "nationalId", "duplicate-national-id"));
    }

    @Test
    void applyingAgainTheSameFileCreatesNothingAndEveryRowIsSkipped() {
        byte[] file = rows(3);
        simulate(file);
        apply(file);
        for (int line = 2; line <= 4; line++) {
            students.problems.add(new RowProblem(line, "legacyId", "already-imported"));
        }
        students.created.clear();

        ImportBatchView again = apply(file).batch();

        assertThat(again.createdRows()).isZero();
        assertThat(again.skippedRows()).isEqualTo(3);
        assertThat(again.rejectedRows()).isZero();
        assertThat(students.created).isEmpty();
        assertThat(students.createCalls).as("no valid row, no call to the registry").isEqualTo(1);
    }

    @Test
    void aConflictAfterTheJudgementIsCountedAsSkippedOrRejectedAndListed() {
        byte[] file = rows(4);
        simulate(file);
        students.outcomes = row -> switch (row.legacyId()) {
            case "R2" -> CreateOutcome.ALREADY_IMPORTED;
            case "R3" -> CreateOutcome.DUPLICATE_NATIONAL_ID;
            default -> CreateOutcome.CREATED;
        };

        ImportRun run = apply(file);

        ImportBatchView batch = run.batch();
        assertThat(batch.createdRows()).isEqualTo(2);
        assertThat(batch.skippedRows()).isEqualTo(1);
        assertThat(batch.rejectedRows()).isEqualTo(1);
        assertThat(batch.totalRows()).isEqualTo(batch.createdRows() + batch.rejectedRows() + batch.skippedRows());
        assertThat(run.rejections()).containsExactly(new RejectionView(4, "nationalId", "duplicate-national-id"),
                new RejectionView(3, "legacyId", "already-imported"));
    }

    @Test
    void ifTheRegistryFailsNoBatchIsRecordedAndTheApplicationCanBeRetried() {
        byte[] file = rows(2);
        simulate(file);
        students.failure = new IllegalStateException("database down");

        assertThatThrownBy(() -> apply(file)).hasMessage("database down");
        assertThat(batches.rows).hasSize(1);
        assertThat(audit).hasSize(1);

        students.failure = null;
        assertThat(apply(file).batch().createdRows()).isEqualTo(2);
    }

    @Test
    void ifTheRegistryFailsAfterCommittingSomeBlocksAFailedEntryWithThePartialCountsIsAuditedAndTheFailureRethrown() {
        byte[] file = rows(3);
        simulate(file);
        IllegalStateException cause = new IllegalStateException("database down");
        students.failure = new PartialImportException(2, cause);

        assertThatThrownBy(() -> apply(file)).isSameAs(cause);

        assertThat(batches.rows).as("no applied batch").hasSize(1);
        assertThat(audit).hasSize(2);
        AuditEntry failed = audit.getLast();
        assertThat(failed.action()).isEqualTo("imports.batch.failed");
        assertThat(failed.outcome()).isEqualTo("error");
        assertThat(failed.entityType()).isEqualTo("imports.batch");
        assertThat(failed.actorId()).isEqualTo(actor.accountId());
        assertThat(failed.institutionId()).isEqualTo(institutionUuid);
        assertThat(failed.afterValue()).contains("\"status\":\"FAILED\"", "\"totalRows\":3",
                "\"createdRows\":2", "\"fileSha256\":\"" + sha256(file) + "\"")
                .doesNotContain("Nombre", "Apellido", "R1", "database down");
        assertThat(failed.reason()).isNull();
    }

    @Test
    void ifTheBatchCannotBeRecordedAfterTheStudentsWereCreatedAFailedEntryIsAuditedToo() {
        byte[] file = rows(2);
        simulate(file);
        IllegalStateException cause = new IllegalStateException("batch table down");
        batches.insertFailure = cause;

        assertThatThrownBy(() -> apply(file)).isSameAs(cause);

        assertThat(audit.getLast().action()).isEqualTo("imports.batch.failed");
        assertThat(audit.getLast().afterValue()).contains("\"createdRows\":2", "\"totalRows\":2");
    }

    @Test
    void ifNothingWasCreatedBeforeTheFailureNoFailedEntryIsWritten() {
        byte[] file = rows(2);
        simulate(file);
        batches.insertFailure = new IllegalStateException("batch table down");
        students.outcomes = row -> CreateOutcome.ALREADY_IMPORTED;

        assertThatThrownBy(() -> apply(file)).hasMessage("batch table down");

        assertThat(audit).extracting(AuditEntry::action).containsExactly("imports.batch.simulated");
    }

    @Test
    void ifTheFailedEntryCannotBeWrittenTheOriginalFailureStillPropagatesWithTheOtherSuppressed() {
        byte[] file = rows(2);
        simulate(file);
        IllegalStateException cause = new IllegalStateException("database down");
        students.failure = new PartialImportException(1, cause);
        IllegalStateException auditDown = new IllegalStateException("audit down");
        AuditLogWriter failing = entry -> {
            throw auditDown;
        };
        ManageImports broken = new ManageImports(runner, batches, students, new FakeGuardianGateways.Guardians(),
                new FakeGuardianGateways.Links(), failing, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> broken.apply(ImportKind.STUDENTS, actor, requestId, file)).isSameAs(cause)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(auditDown));
    }

    // --- Audit ---

    @Test
    void theAuditEntriesCarryCountsAndTheFingerprintAndNothingOfTheFile() {
        byte[] file = file(HEADER + "REF-SECRETA,Wenceslao-Marcador,Apellido-Marcador,2012-05-20\n");
        students.problems.add(new RowProblem(2, "birthDate", "invalid-date"));
        simulate(file);
        students.problems.clear();
        apply(file);

        assertThat(audit).extracting(AuditEntry::action).containsExactly("imports.batch.simulated",
                "imports.batch.applied");
        assertThat(audit).extracting(AuditEntry::entityType).containsOnly("imports.batch");
        assertThat(audit).extracting(AuditEntry::entityId).containsExactly(
                batches.rows.get(0).id().toString(), batches.rows.get(1).id().toString());
        assertThat(audit).allSatisfy(entry -> {
            assertThat(entry.actorId()).isEqualTo(actor.accountId());
            assertThat(entry.institutionId()).isEqualTo(institutionUuid);
            assertThat(entry.afterValue()).contains("\"fileSha256\":\"" + sha256(file) + "\"")
                    .contains("\"kind\":\"STUDENTS\"");
            assertThat(entry.toString() + entry.afterValue()).doesNotContain("SECRETA").doesNotContain("Marcador")
                    .doesNotContain("2012-05-20").doesNotContain("birthDate");
        });
        assertThat(audit.get(0).afterValue()).contains("\"status\":\"VALIDATED\"").contains("\"rejectedRows\":1");
        assertThat(audit.get(1).afterValue()).contains("\"status\":\"APPLIED\"").contains("\"createdRows\":1");
    }

    @Test
    void aBatchHoldsNothingOfTheFileButCountsAndTheFingerprint() {
        simulate(file(HEADER + "REF-SECRETA,Wenceslao-Marcador,Apellido-Marcador,2012-05-20\n"));

        assertThat(batches.rows.getFirst().toString()).doesNotContain("SECRETA").doesNotContain("Marcador");
        assertThat(java.util.Arrays.stream(ImportBatch.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("id", "kind", "status", "fileSha256", "totalRows", "validRows", "rejectedRows",
                        "createdRows", "skippedRows", "simulationId", "createdBy", "createdAt", "appliedAt");
    }

    // --- History ---

    @Test
    void theHistoryIsNewestFirstPaginatedAndScopedToTheInstitution() {
        for (int i = 1; i <= 3; i++) {
            simulate(rows(i));
        }
        imports.simulate(ImportKind.STUDENTS, actorOf(UUID.randomUUID(), UUID.randomUUID()), requestId, rows(1));

        Page<ImportBatchView> first = imports.list(actor, requestId, 0, 2, ALL);
        Page<ImportBatchView> second = imports.list(actor, requestId, 1, 2, ALL);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(ImportBatchView::totalRows).containsExactly(3, 2);
        assertThat(second.items()).extracting(ImportBatchView::totalRows).containsExactly(1);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(2);
    }

    @Test
    void aPageOrSizeOutOfRangeIsRefusedBeforeReading() {
        assertThatThrownBy(() -> imports.list(actor, requestId, -1, 10, ALL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.list(actor, requestId, 0, 0, ALL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.list(actor, requestId, 0, PageRequest.MAX_SIZE + 1, ALL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Guardians and links (docs/cortes/imports-guardians.md) ---

    private static final String GUARDIANS_HEADER = "legacyId,firstName,lastName,nationalId\n";
    private static final String LINKS_HEADER =
            "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer\n";

    private ImportRun simulate(ImportKind kind, String text) {
        return imports.simulate(kind, actor, requestId, file(text));
    }

    private ImportRun apply(ImportKind kind, String text) {
        return imports.apply(kind, actor, requestId, file(text));
    }

    @Test
    void aLinksFileWithARowOfTheWrongShapeIsRefusedWholeAndNothingIsRecorded() {
        String text = LINKS_HEADER + "S1,G1,MOTHER,100,true\nS1,G2,FATHER,50\n";
        assertThat(refusal(() -> simulate(ImportKind.GUARDIAN_LINKS, text)))
                .containsExactly(new RejectedImportFileException.FileProblem("file", "row-shape"));
        // Such a file has no simulation, so applying it stops at the binding before it is read.
        assertThatThrownBy(() -> apply(ImportKind.GUARDIAN_LINKS, text)).isInstanceOf(ImportNotSimulatedException.class);
        assertThat(batches.rows).isEmpty();
        assertThat(linkPort.evaluated).isEmpty();
        assertThat(linkPort.applied).isEmpty();
    }

    @Test
    void aLinksFileWithARowWithoutAUsableStudentReferenceIsRefusedWholeButOtherProblemsAreNot() {
        String text = LINKS_HEADER + "S1,G1,MOTHER,100,true\n,G2,FATHER,50,\n";
        for (String reason : java.util.List.of("required", "invalid-format", "too-long")) {
            linkPort.problems.clear();
            linkPort.problems.add(FakeGuardianGateways.problem(3, "studentLegacyId", reason));
            assertThat(refusal(() -> simulate(ImportKind.GUARDIAN_LINKS, text)))
                    .containsExactly(new RejectedImportFileException.FileProblem("file", "row-shape"));
        }
        assertThat(batches.rows).isEmpty();
        linkPort.problems.clear();
        linkPort.problems.add(FakeGuardianGateways.problem(3, "studentLegacyId", "unknown-student"));
        linkPort.problems.add(FakeGuardianGateways.problem(3, "guardianLegacyId", "required"));
        assertThat(simulate(ImportKind.GUARDIAN_LINKS, text).batch().rejectedRows()).isEqualTo(1);
    }

    @Test
    void theGuardiansReachThePortRawAndAreCreatedOnApplicationWithTheirOwnKindOfBatch() {
        String text = GUARDIANS_HEADER + "P1,Rosa,Aguilar,0801-1980-12345\nP2,Luis,Alvarado,\n";
        ImportRun simulation = simulate(ImportKind.GUARDIANS, text);
        assertThat(simulation.batch().kind()).isEqualTo("GUARDIANS");
        assertThat(simulation.batch().validRows()).isEqualTo(2);
        assertThat(guardianPort.created).isEmpty();
        assertThat(guardianPort.evaluated).extracting(g -> g.legacyId()).containsExactly("P1", "P2");

        ImportRun applied = apply(ImportKind.GUARDIANS, text);

        assertThat(applied.batch().status()).isEqualTo("APPLIED");
        assertThat(applied.batch().createdRows()).isEqualTo(2);
        assertThat(guardianPort.created).hasSize(2);
        assertThat(students.evaluated).isEmpty();
        assertThat(audit.toString()).doesNotContain("Rosa", "Aguilar", "0801").doesNotContain("P1");
    }

    @Test
    void aSimulationOfAnotherKindNeverAllowsTheApplication() {
        String text = GUARDIANS_HEADER + "P1,Rosa,Aguilar,\n";
        simulate(ImportKind.GUARDIANS, text);

        assertThatThrownBy(() -> apply(ImportKind.GUARDIAN_LINKS, text)).isInstanceOf(ImportNotSimulatedException.class);
        assertThatThrownBy(() -> apply(ImportKind.STUDENTS, text)).isInstanceOf(ImportNotSimulatedException.class);
    }

    @Test
    void theGuardiansPortProblemsAndLateOutcomesAreCountedLikeThoseOfTheStudents() {
        guardianPort.problems.add(FakeGuardianGateways.problem(2, "email", "invalid-format"));
        guardianPort.problems.add(FakeGuardianGateways.problem(3, "legacyId", "already-imported"));
        guardianPort.outcomes = row -> switch (row.legacyId()) {
            case "P5" -> com.confia.guardians.application.importing.CreateOutcome.DUPLICATE_NATIONAL_ID;
            case "P6" -> com.confia.guardians.application.importing.CreateOutcome.ALREADY_IMPORTED;
            default -> com.confia.guardians.application.importing.CreateOutcome.CREATED;
        };
        String text = GUARDIANS_HEADER + "P1,a,b,\nP2,a,b,\nP4,a,b,\nP5,a,b,\nP6,a,b,\n";
        ImportRun simulation = simulate(ImportKind.GUARDIANS, text);
        assertThat(simulation.batch().rejectedRows()).isEqualTo(1);
        assertThat(simulation.batch().skippedRows()).isEqualTo(1);
        assertThat(simulation.batch().validRows()).isEqualTo(3);

        ImportRun applied = apply(ImportKind.GUARDIANS, text);

        assertThat(applied.batch().createdRows()).isEqualTo(1);
        assertThat(applied.batch().rejectedRows()).isEqualTo(2);
        assertThat(applied.batch().skippedRows()).isEqualTo(2);
        assertThat(applied.batch().totalRows()).isEqualTo(5);
        assertThat(applied.rejections()).extracting(RejectionView::reason).containsExactly("invalid-format",
                "duplicate-national-id", "already-imported", "already-imported");
    }

    @Test
    void aGuardiansFileWithTheHeaderOfStudentsIsRefusedWithAPositionAndNeverTheText() {
        assertThat(refusal(() -> simulate(ImportKind.GUARDIANS, "legacyId,firstName,lastName,birthDate\nP1,a,b,c\n")))
                .containsExactly(new RejectedImportFileException.FileProblem("columns[3]", "unknown-column"));
    }

    @Test
    void aPartialFailureOfTheGuardiansLeavesTheTraceOfItsKindAndRethrowsTheCause() {
        IllegalStateException cause = new IllegalStateException("database down");
        guardianPort.failure = new com.confia.guardians.application.importing.PartialImportException(3, cause);
        String text = GUARDIANS_HEADER + "P1,a,b,\n";
        simulate(ImportKind.GUARDIANS, text);

        assertThatThrownBy(() -> apply(ImportKind.GUARDIANS, text)).isSameAs(cause);

        AuditEntry failed = audit.getLast();
        assertThat(failed.action()).isEqualTo("imports.batch.failed");
        assertThat(failed.afterValue()).contains("GUARDIANS").contains("\"createdRows\":3");
    }

    @Test
    void theLinksAreGroupedByThePortAndAStudentAlreadyLinkedIsSkippedNotRejected() {
        linkPort.problems.add(FakeGuardianGateways.problem(2, "studentLegacyId", "student-already-linked"));
        linkPort.problems.add(FakeGuardianGateways.problem(3, "studentLegacyId", "student-already-linked"));
        linkPort.problems.add(FakeGuardianGateways.problem(4, "row", "group-rejected"));
        String text = LINKS_HEADER + "S1,G1,MOTHER,60,true\nS1,G2,FATHER,40,\nS2,G1,MOTHER,100,\nS3,G1,MOTHER,100,\n";

        ImportRun simulation = simulate(ImportKind.GUARDIAN_LINKS, text);

        assertThat(simulation.batch().kind()).isEqualTo("GUARDIAN_LINKS");
        assertThat(simulation.batch().skippedRows()).isEqualTo(2);
        assertThat(simulation.batch().rejectedRows()).isEqualTo(1);
        assertThat(simulation.batch().validRows()).isEqualTo(1);
        assertThat(simulation.rejections()).extracting(RejectionView::line).containsExactly(4, 2, 3);
        assertThat(linkPort.evaluated).hasSize(4);
        assertThat(linkPort.evaluated.getFirst().financialResponsibilityPercent()).isEqualTo("60");
    }

    @Test
    void theOutcomesOfTheSetsOfLinksAreReportedPerLine() {
        linkPort.outcomes = row -> switch (row.studentLegacyId()) {
            case "S1" -> LinkOutcome.CREATED;
            case "S2" -> LinkOutcome.ALREADY_LINKED;
            case "S3" -> LinkOutcome.STUDENT_NOT_ACTIVE;
            default -> LinkOutcome.GUARDIAN_NOT_ACTIVE;
        };
        String text = LINKS_HEADER + "S1,G1,MOTHER,100,\nS2,G1,MOTHER,100,\nS3,G1,MOTHER,100,\nS4,G1,MOTHER,100,\n";
        simulate(ImportKind.GUARDIAN_LINKS, text);

        ImportRun applied = apply(ImportKind.GUARDIAN_LINKS, text);

        assertThat(applied.batch().createdRows()).isEqualTo(1);
        assertThat(applied.batch().skippedRows()).isEqualTo(1);
        assertThat(applied.batch().rejectedRows()).isEqualTo(2);
        assertThat(applied.rejections()).extracting(RejectionView::reason).containsExactly("student-not-active",
                "guardian-not-active", "student-already-linked");
        assertThat(linkPort.applied).extracting(l -> l.studentLegacyId()).containsExactly("S1");
    }

    @Test
    void aPartialFailureOfTheLinksLeavesTheTraceOfItsKind() {
        IllegalStateException cause = new IllegalStateException("database down");
        linkPort.failure = new com.confia.guardians.application.importing.PartialImportException(2, cause);
        String text = LINKS_HEADER + "S1,G1,MOTHER,100,\n";
        simulate(ImportKind.GUARDIAN_LINKS, text);

        assertThatThrownBy(() -> apply(ImportKind.GUARDIAN_LINKS, text)).isSameAs(cause);
        assertThat(audit.getLast().afterValue()).contains("GUARDIAN_LINKS").contains("\"createdRows\":2");
    }

    @Test
    void theHistoryOnlyListsTheKindsItIsGiven() {
        simulate(ImportKind.GUARDIANS, GUARDIANS_HEADER + "P1,a,b,\n");
        simulate(ImportKind.GUARDIAN_LINKS, LINKS_HEADER + "S1,G1,MOTHER,100,\n");
        simulate(rows(1));

        assertThat(imports.list(actor, requestId, 0, 10, ALL).total()).isEqualTo(3);
        assertThat(imports.list(actor, requestId, 0, 10, Set.of(ImportKind.GUARDIANS)).items())
                .extracting(ImportBatchView::kind).containsExactly("GUARDIANS");
        assertThat(imports.list(actor, requestId, 0, 10, Set.of()).total()).isZero();
    }

    @Test
    void theRoutesNameTheKindAndTheHistoryFiltersByItAccordingToWhatTheAccountMayRead() {
        String guardians = GUARDIANS_HEADER + "P1,a,b,\n";
        ImportRun run = imports.simulate("guardians", actor, requestId, file(guardians));
        assertThat(run.batch().kind()).isEqualTo("GUARDIANS");
        imports.apply("guardians", actor, requestId, file(guardians));
        assertThatThrownBy(() -> imports.simulate("nope", actor, requestId, rows(1)))
                .isInstanceOf(UnknownImportKindException.class);
        simulate(rows(1));

        assertThat(imports.list(actor, requestId, 0, 10, null, true, true).total()).isEqualTo(3);
        assertThat(imports.list(actor, requestId, 0, 10, null, true, false).total()).isEqualTo(1);
        assertThat(imports.list(actor, requestId, 0, 10, null, false, false).total()).isZero();
        assertThat(imports.list(actor, requestId, 0, 10, "guardians", true, true).total()).isEqualTo(2);
        assertThatThrownBy(() -> imports.list(actor, requestId, 0, 10, "guardians", true, false))
                .isInstanceOfSatisfying(KindNotReadableException.class, e -> assertThat(e.students()).isFalse());
        assertThatThrownBy(() -> imports.list(actor, requestId, 0, 10, "students", false, true))
                .isInstanceOfSatisfying(KindNotReadableException.class, e -> assertThat(e.students()).isTrue());
        assertThatThrownBy(() -> imports.list(actor, requestId, 0, 10, "nope", true, true))
                .isInstanceOf(UnknownImportKindException.class);
    }

    // --- Ports ---

    private static final class InMemoryBatches implements ImportBatchRepository {

        record Stored(UUID institution, ImportBatch batch) {
        }

        final List<ImportBatch> rows = new ArrayList<>();
        RuntimeException insertFailure;
        private final List<Stored> stored = new ArrayList<>();

        @Override
        public void insert(InstitutionId institutionId, ImportBatch batch) {
            if (insertFailure != null) {
                throw insertFailure;
            }
            rows.add(batch);
            stored.add(new Stored(institutionId.value(), batch));
        }

        /** The batches of the institution, newest first (the order they were inserted, reversed). */
        private List<ImportBatch> of(InstitutionId institutionId) {
            List<ImportBatch> newestFirst = new ArrayList<>();
            for (int i = stored.size() - 1; i >= 0; i--) {
                if (stored.get(i).institution().equals(institutionId.value())) {
                    newestFirst.add(stored.get(i).batch());
                }
            }
            return newestFirst;
        }

        @Override
        public Optional<ImportBatch> findValidated(InstitutionId institutionId, UUID createdBy, ImportKind kind,
                String fileSha256) {
            return of(institutionId).stream().filter(b -> b.createdBy().equals(createdBy) && b.kind() == kind
                    && b.status().name().equals("VALIDATED") && b.fileSha256().equals(fileSha256)).findFirst();
        }

        @Override
        public Optional<ImportBatch> latestOf(InstitutionId institutionId, UUID createdBy, ImportKind kind) {
            return of(institutionId).stream().filter(b -> b.createdBy().equals(createdBy) && b.kind() == kind)
                    .findFirst();
        }

        @Override
        public Page<ImportBatch> list(InstitutionId institutionId, PageRequest request, Set<ImportKind> kinds) {
            List<ImportBatch> all = of(institutionId).stream().filter(b -> kinds.contains(b.kind())).toList();
            int from = (int) Math.min(request.offset(), all.size());
            int to = Math.min(from + request.size(), all.size());
            return new Page<>(all.subList(from, to), all.size(), request.page(), request.size());
        }
    }

    /** The registry as {@code imports} sees it: it answers the problems it is given and records what it creates. */
    private static final class FakeStudents implements StudentImportGateway {

        final List<RowProblem> problems = new ArrayList<>();
        final List<ImportRow> evaluated = new ArrayList<>();
        final List<ImportRow> created = new ArrayList<>();
        int createCalls;
        Function<ImportRow, CreateOutcome> outcomes = row -> CreateOutcome.CREATED;
        RuntimeException failure;

        @Override
        public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<ImportRow> rows) {
            evaluated.clear();
            evaluated.addAll(rows);
            Set<Integer> lines = rows.stream().map(ImportRow::line).collect(java.util.stream.Collectors.toSet());
            return problems.stream().filter(p -> lines.contains(p.line()))
                    .sorted(Comparator.comparingInt(RowProblem::line)).toList();
        }

        @Override
        public List<CreateOutcome> createAll(AuthenticatedActor actor, String requestId, List<ImportRow> rows) {
            createCalls++;
            if (failure != null) {
                throw failure;
            }
            List<CreateOutcome> answers = rows.stream().map(outcomes).toList();
            for (int i = 0; i < rows.size(); i++) {
                if (answers.get(i) == CreateOutcome.CREATED) {
                    created.add(rows.get(i));
                }
            }
            return answers;
        }
    }
}
