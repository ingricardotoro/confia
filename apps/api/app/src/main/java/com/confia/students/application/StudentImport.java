package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.CreateOutcome;
import com.confia.students.application.importing.ImportRow;
import com.confia.students.application.importing.PartialImportException;
import com.confia.students.application.importing.RowProblem;
import com.confia.students.application.importing.StudentImportGateway;
import com.confia.students.domain.DuplicateExternalRefException;
import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.InvalidStudentException;
import com.confia.students.domain.NationalId;
import com.confia.students.domain.StudentFieldCheck;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The bulk import of students as {@code students} offers it to {@code imports}: it judges the rows of a file with the
 * rules of the registry and registers the valid ones through {@link ManageStudents}, the same use case as the hand
 * registration, so each student gets the generated code, the encrypted document with its blind index and the audit
 * entry that a hand registration gets. It restates no rule: the field rules are {@code StudentFieldCheck}'s, the
 * document is {@code NationalId}'s and the reference lookup goes through the registry's own repository. The judgment
 * never queries the blind index of the stored documents (it would be a mass oracle over the documents of minors): a
 * document another student holds is only found when a row is registered, like a hand registration.
 *
 * <p>Nothing here keeps, returns or logs a cell: a problem is a line, a column name and a reason.
 *
 * <p>The rows are registered in blocks that commit together, because one transaction per student would make a file of
 * ten thousand rows slow. A block that fails because of a conflict (a reference or a document taken in between, which
 * the unique indexes settle) is rolled back whole and its rows are retried one by one, each in its own transaction, so
 * one conflict never discards the others and never leaves half a block.
 */
public final class StudentImport implements StudentImportGateway {

    static final int BLOCK_SIZE = 100;
    static final int LOOKUP_CHUNK = 1000;

    private final TransactionRunner transactionRunner;
    private final StudentRepository students;
    private final ManageStudents manageStudents;
    private final Clock clock;

    public StudentImport(TransactionRunner transactionRunner, StudentRepository students,
            ManageStudents manageStudents, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.manageStudents = Objects.requireNonNull(manageStudents, "manageStudents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The line and the reference of a row that passed its field rules and is not repeated in the file. */
    private record Check(int line, String reference) {
    }

    @Override
    public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<ImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Instant now = clock.instant();

        List<RowProblem> problems = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Map<String, Integer> referenceLines = new HashMap<>();
        Map<String, Integer> documentLines = new HashMap<>();
        for (ImportRow row : rows) {
            List<StudentFieldCheck.Problem> fieldProblems = StudentFieldCheck.problems(row.legacyId(),
                    row.firstName(), row.lastName(), row.birthDate(), row.sex(), row.nationalId(), now);
            for (StudentFieldCheck.Problem problem : fieldProblems) {
                problems.add(new RowProblem(row.line(), problem.field(), problem.reason()));
            }
            String reference = fieldProblems.stream().anyMatch(p -> p.field().equals("legacyId")) ? null
                    : StudentFieldCheck.externalRef(row.legacyId());
            if (reference != null && referenceLines.putIfAbsent(reference, row.line()) != null) {
                problems.add(new RowProblem(row.line(), "legacyId", "duplicate-in-file"));
                reference = null;
            }
            NationalId document = fieldProblems.stream().anyMatch(p -> p.field().equals("nationalId"))
                    || StudentFieldCheck.nationalIdOf(row.nationalId()) == null ? null
                    : NationalId.of(StudentFieldCheck.nationalIdOf(row.nationalId()));
            if (document != null && documentLines.putIfAbsent(document.normalized(), row.line()) != null) {
                problems.add(new RowProblem(row.line(), "nationalId", "duplicate-in-file"));
                document = null;
            }
            if (reference != null) {
                checks.add(new Check(row.line(), reference));
            }
        }
        problems.addAll(transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> storedReferences(institution, checks)));
        problems.sort(Comparator.comparingInt(RowProblem::line));
        return List.copyOf(problems);
    }

    /**
     * The rows whose reference the institution already has ({@code already-imported}, a low-sensitivity key of the
     * source system); one read per thousand rows. The stored national ids are deliberately NOT consulted here: a
     * judgment that answers "this document is already registered" for a whole file without a registration would be a
     * mass oracle over the documents of minors. A document held by another student is found by {@link #createAll},
     * where each attempt is a real, audited registration like a hand registration.
     */
    private List<RowProblem> storedReferences(InstitutionId institution, List<Check> checks) {
        Set<String> storedReferences = new HashSet<>();
        List<String> references = checks.stream().map(Check::reference).toList();
        for (List<String> chunk : chunks(references)) {
            storedReferences.addAll(students.existingExternalRefs(institution, chunk));
        }
        List<RowProblem> problems = new ArrayList<>();
        for (Check check : checks) {
            if (storedReferences.contains(check.reference())) {
                problems.add(new RowProblem(check.line(), "legacyId", "already-imported"));
            }
        }
        return problems;
    }

    private static <T> List<List<T>> chunks(List<T> values) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += LOOKUP_CHUNK) {
            chunks.add(values.subList(from, Math.min(from + LOOKUP_CHUNK, values.size())));
        }
        return chunks;
    }

    @Override
    public List<CreateOutcome> createAll(AuthenticatedActor actor, String requestId, List<ImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        List<CreateOutcome> outcomes = new ArrayList<>(rows.size());
        try {
            for (int from = 0; from < rows.size(); from += BLOCK_SIZE) {
                List<ImportRow> block = rows.subList(from, Math.min(from + BLOCK_SIZE, rows.size()));
                try {
                    transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                        block.forEach(row -> register(actor, auditRequestId.toString(), row));
                        return null;
                    });
                    block.forEach(row -> outcomes.add(CreateOutcome.CREATED));
                } catch (DuplicateExternalRefException | DuplicateNationalIdException conflict) {
                    for (ImportRow row : block) {
                        outcomes.add(registerAlone(actor, auditRequestId, row));
                    }
                }
            }
        } catch (RuntimeException failure) {
            // Blocks already committed stay: the caller is told how many, to leave a trace of the partial result.
            int created = (int) outcomes.stream().filter(outcome -> outcome == CreateOutcome.CREATED).count();
            if (created > 0) {
                throw new PartialImportException(created, failure);
            }
            throw failure;
        }
        return List.copyOf(outcomes);
    }

    private CreateOutcome registerAlone(AuthenticatedActor actor, UUID auditRequestId, ImportRow row) {
        try {
            transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                register(actor, auditRequestId.toString(), row);
                return null;
            });
            return CreateOutcome.CREATED;
        } catch (DuplicateExternalRefException e) {
            return CreateOutcome.ALREADY_IMPORTED;
        } catch (DuplicateNationalIdException e) {
            return CreateOutcome.DUPLICATE_NATIONAL_ID;
        }
    }

    /** One student through the use case of the registry; a row that breaks a rule here was not evaluated. */
    private void register(AuthenticatedActor actor, String requestId, ImportRow row) {
        if (!StudentFieldCheck.problems(row.legacyId(), row.firstName(), row.lastName(), row.birthDate(), row.sex(),
                row.nationalId(), clock.instant()).isEmpty()) {
            throw new InvalidStudentException();
        }
        manageStudents.createImported(actor, requestId, StudentFieldCheck.externalRef(row.legacyId()),
                row.firstName(), row.lastName(), StudentFieldCheck.birthDateOf(row.birthDate()),
                StudentFieldCheck.sexOf(row.sex()), StudentFieldCheck.nationalIdOf(row.nationalId()));
    }
}
