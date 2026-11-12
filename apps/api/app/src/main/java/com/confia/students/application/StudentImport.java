package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.importing.CreateOutcome;
import com.confia.students.application.importing.ImportRow;
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
 * document is {@code NationalId}'s and the lookups go through the registry's own repository and blind index.
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
    private final NationalIdIndexes indexes;
    private final ManageStudents manageStudents;
    private final Clock clock;

    public StudentImport(TransactionRunner transactionRunner, StudentRepository students, NationalIdIndexes indexes,
            ManageStudents manageStudents, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.manageStudents = Objects.requireNonNull(manageStudents, "manageStudents");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the lookups need to know about a row that passed its field rules. */
    private record Check(int line, String reference, NationalId document) {
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
            if (reference != null || document != null) {
                checks.add(new Check(row.line(), reference, document));
            }
        }
        problems.addAll(transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> storedConflicts(institution, checks)));
        problems.sort(Comparator.comparingInt(RowProblem::line));
        return List.copyOf(problems);
    }

    /** The rows whose reference or document the institution already has; two reads per thousand rows. */
    private List<RowProblem> storedConflicts(InstitutionId institution, List<Check> checks) {
        Set<String> storedReferences = new HashSet<>();
        List<String> references = checks.stream().map(Check::reference).filter(Objects::nonNull).toList();
        for (List<String> chunk : chunks(references)) {
            storedReferences.addAll(students.existingExternalRefs(institution, chunk));
        }
        Map<Integer, List<String>> indexesOfLine = new HashMap<>();
        Set<String> allIndexes = new HashSet<>();
        for (Check check : checks) {
            boolean alreadyImported = check.reference() != null && storedReferences.contains(check.reference());
            if (check.document() != null && !alreadyImported) {
                List<String> lookup = indexes.lookupIndexes(institution, check.document());
                indexesOfLine.put(check.line(), lookup);
                allIndexes.addAll(lookup);
            }
        }
        Set<String> storedIndexes = new HashSet<>();
        for (List<String> chunk : chunks(List.copyOf(allIndexes))) {
            storedIndexes.addAll(students.existingBlindIndexes(institution, chunk));
        }
        List<RowProblem> problems = new ArrayList<>();
        for (Check check : checks) {
            if (check.reference() != null && storedReferences.contains(check.reference())) {
                problems.add(new RowProblem(check.line(), "legacyId", "already-imported"));
            } else if (indexesOfLine.getOrDefault(check.line(), List.of()).stream().anyMatch(storedIndexes::contains)) {
                problems.add(new RowProblem(check.line(), "nationalId", "duplicate-national-id"));
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
