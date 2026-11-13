package com.confia.guardians.application;

import com.confia.guardians.application.importing.CreateOutcome;
import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianImportRow;
import com.confia.guardians.application.importing.PartialImportException;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.guardians.domain.DuplicateExternalRefException;
import com.confia.guardians.domain.DuplicateNationalIdException;
import com.confia.guardians.domain.GuardianFieldCheck;
import com.confia.guardians.domain.InvalidGuardianException;
import com.confia.guardians.domain.NationalId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
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
 * The bulk import of guardians as {@code guardians} offers it to {@code imports} (docs/cortes/imports-guardians.md): it
 * judges the rows of a file with the rules of the registry and registers the valid ones through {@link
 * ManageGuardians}, the same use case as the hand registration, so each guardian gets the generated code, the
 * encrypted document and RTN with the blind index and the audit entry that a hand registration gets. It restates no
 * rule: the field rules are {@code GuardianFieldCheck}'s, the document is {@code NationalId}'s and the reference lookup
 * goes through the registry's own repository. The judgment never queries the blind index of the stored documents (it
 * would be a mass oracle over the documents of guardians): a document another guardian holds is only found when a row
 * is registered, like a hand registration. The RTN has no uniqueness and no lookup.
 *
 * <p>Nothing here keeps, returns or logs a cell: a problem is a line, a column name and a reason.
 *
 * <p>The rows are registered in blocks that commit together, because one transaction per guardian would make a file
 * of ten thousand rows slow. A block that fails because of a conflict (a reference or a document taken in between,
 * which the unique indexes settle) is rolled back whole and its rows are retried one by one, each in its own
 * transaction, so one conflict never discards the others and never leaves half a block.
 */
public final class GuardianImport implements GuardianImportGateway {

    static final int BLOCK_SIZE = 100;
    static final int LOOKUP_CHUNK = 1000;

    private final TransactionRunner transactionRunner;
    private final GuardianRepository guardians;
    private final ManageGuardians manageGuardians;

    public GuardianImport(TransactionRunner transactionRunner, GuardianRepository guardians,
            ManageGuardians manageGuardians) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.manageGuardians = Objects.requireNonNull(manageGuardians, "manageGuardians");
    }

    /** The line and the reference of a row that passed its field rules and is not repeated in the file. */
    private record Check(int line, String reference) {
    }

    @Override
    public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<GuardianImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());

        List<RowProblem> problems = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Map<String, Integer> referenceLines = new HashMap<>();
        Map<String, Integer> documentLines = new HashMap<>();
        for (GuardianImportRow row : rows) {
            List<GuardianFieldCheck.Problem> fieldProblems = fieldProblems(row);
            fieldProblems.forEach(problem -> problems.add(new RowProblem(row.line(), problem.field(),
                    problem.reason())));
            String reference = hasProblemOn(fieldProblems, "legacyId") ? null
                    : GuardianFieldCheck.externalRef(row.legacyId());
            if (reference != null && referenceLines.putIfAbsent(reference, row.line()) != null) {
                problems.add(new RowProblem(row.line(), "legacyId", "duplicate-in-file"));
                reference = null;
            }
            String document = hasProblemOn(fieldProblems, "nationalId")
                    || GuardianFieldCheck.optionalOf(row.nationalId()) == null ? null
                    : NationalId.of(row.nationalId()).normalized();
            if (document != null && documentLines.putIfAbsent(document, row.line()) != null) {
                problems.add(new RowProblem(row.line(), "nationalId", "duplicate-in-file"));
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
     * mass oracle over the documents. A document held by another guardian is found by {@link #createAll}, where each
     * attempt is a real, audited registration like a hand registration.
     */
    private List<RowProblem> storedReferences(InstitutionId institution, List<Check> checks) {
        Set<String> stored = new HashSet<>();
        List<String> references = checks.stream().map(Check::reference).toList();
        for (int from = 0; from < references.size(); from += LOOKUP_CHUNK) {
            stored.addAll(guardians.existingExternalRefs(institution,
                    references.subList(from, Math.min(from + LOOKUP_CHUNK, references.size()))));
        }
        List<RowProblem> problems = new ArrayList<>();
        for (Check check : checks) {
            if (stored.contains(check.reference())) {
                problems.add(new RowProblem(check.line(), "legacyId", "already-imported"));
            }
        }
        return problems;
    }

    @Override
    public List<CreateOutcome> createAll(AuthenticatedActor actor, String requestId, List<GuardianImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        List<CreateOutcome> outcomes = new ArrayList<>(rows.size());
        try {
            for (int from = 0; from < rows.size(); from += BLOCK_SIZE) {
                List<GuardianImportRow> block = rows.subList(from, Math.min(from + BLOCK_SIZE, rows.size()));
                try {
                    transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                        block.forEach(row -> register(actor, auditRequestId.toString(), row, false));
                        return null;
                    });
                    block.forEach(row -> outcomes.add(CreateOutcome.CREATED));
                } catch (DuplicateExternalRefException | DuplicateNationalIdException conflict) {
                    for (GuardianImportRow row : block) {
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

    private CreateOutcome registerAlone(AuthenticatedActor actor, UUID auditRequestId, GuardianImportRow row) {
        try {
            register(actor, auditRequestId.toString(), row, true);
            return CreateOutcome.CREATED;
        } catch (DuplicateExternalRefException e) {
            return CreateOutcome.ALREADY_IMPORTED;
        } catch (DuplicateNationalIdException e) {
            return CreateOutcome.DUPLICATE_NATIONAL_ID;
        }
    }

    /**
     * One guardian through the use case of the registry; a row that breaks a rule here was not evaluated. On its own
     * ({@code alone}) the use case opens its transaction and records a refusal for a duplicate document.
     */
    private void register(AuthenticatedActor actor, String requestId, GuardianImportRow row, boolean alone) {
        if (!fieldProblems(row).isEmpty()) {
            throw new InvalidGuardianException();
        }
        String reference = GuardianFieldCheck.externalRef(row.legacyId());
        String firstName = row.firstName();
        String lastName = row.lastName();
        String nationalId = GuardianFieldCheck.optionalOf(row.nationalId());
        String rtn = GuardianFieldCheck.optionalOf(row.rtn());
        String email = GuardianFieldCheck.optionalOf(row.email());
        String phone = GuardianFieldCheck.optionalOf(row.phone());
        String address = GuardianFieldCheck.optionalOf(row.address());
        String channel = GuardianFieldCheck.optionalOf(row.preferredChannel());
        if (alone) {
            manageGuardians.createImportedAlone(actor, requestId, reference, firstName, lastName, nationalId, rtn,
                    email, phone, address, channel);
        } else {
            manageGuardians.createImported(actor, requestId, reference, firstName, lastName, nationalId, rtn, email,
                    phone, address, channel);
        }
    }

    private static List<GuardianFieldCheck.Problem> fieldProblems(GuardianImportRow row) {
        return GuardianFieldCheck.problems(row.legacyId(), row.firstName(), row.lastName(), row.nationalId(),
                row.rtn(), row.email(), row.phone(), row.address(), row.preferredChannel());
    }

    private static boolean hasProblemOn(List<GuardianFieldCheck.Problem> problems, String field) {
        return problems.stream().anyMatch(problem -> problem.field().equals(field));
    }
}
