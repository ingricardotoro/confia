package com.confia.imports.application;

import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.guardians.application.importing.LinkImportRow;
import com.confia.guardians.application.importing.LinkOutcome;
import com.confia.guardians.application.importing.PartialImportException;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.imports.domain.CsvTemplate;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.InvalidImportFileException;
import com.confia.imports.domain.Rejection;
import com.confia.shared.security.AuthenticatedActor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The links between guardians and students: the rows are grouped by student by {@code guardians}, which judges each
 * group as the complete set that the hand registration demands (INV-32) and registers it with the same use case as the
 * replacement of the set. The references to the students and the guardians are those of the source system, resolved
 * against the ones already imported. The import never replaces links that exist.
 */
final class GuardianLinksProcessor implements ImportProcessor {

    /** The reason of a file refused because a row cannot be attributed to the set of a student. */
    static final String ROW_SHAPE = "row-shape";

    private static final Set<String> UNUSABLE = Set.of("required", "invalid-format", "too-long");

    private final GuardianLinkImportGateway links;

    GuardianLinksProcessor(GuardianLinkImportGateway links) {
        this.links = Objects.requireNonNull(links, "links");
    }

    @Override
    public ImportKind kind() {
        return ImportKind.GUARDIAN_LINKS;
    }

    @Override
    public Judgement judge(AuthenticatedActor actor, String requestId, String text, int maxRows) {
        CsvTemplate.Parsed parsed = ImportKind.GUARDIAN_LINKS.template().parse(text, maxRows);
        if (!parsed.shapeRejections().isEmpty()) {
            // A row of the wrong shape cannot be attributed to the set of any student: importing the others could
            // register a set without it, so the whole file is refused.
            throw new InvalidImportFileException("file", ROW_SHAPE);
        }
        List<LinkImportRow> rows = parsed.rows().stream()
                .map(row -> new LinkImportRow(row.line(), row.cell("studentLegacyId"), row.cell("guardianLegacyId"),
                        row.cell("relationship"), row.cell("financialResponsibilityPercent"),
                        row.cell("isPrimaryPayer"), row.cell("isBillingRecipient")))
                .toList();
        List<RowProblem> problems = rows.isEmpty() ? List.of() : links.evaluate(actor, requestId, rows);
        if (problems.stream().anyMatch(GuardianLinksProcessor::studentReferenceUnusable)) {
            // Nor can a row without a usable student reference: it may belong to the set of any student.
            throw new InvalidImportFileException("file", ROW_SHAPE);
        }

        List<Rejection> rejections = new ArrayList<>(parsed.shapeRejections());
        Set<Integer> problemLines = new HashSet<>();
        for (RowProblem problem : problems) {
            rejections.add(new Rejection(problem.line(), problem.field(), problem.reason()));
            problemLines.add(problem.line());
        }
        List<LinkImportRow> valid = rows.stream().filter(row -> !problemLines.contains(row.line())).toList();
        return new Judgement(parsed.totalRows(), rejections, () -> register(actor, requestId, valid));
    }

    private static boolean studentReferenceUnusable(RowProblem problem) {
        return problem.field().equals("studentLegacyId") && UNUSABLE.contains(problem.reason());
    }

    private Registration register(AuthenticatedActor actor, String requestId, List<LinkImportRow> valid) {
        if (valid.isEmpty()) {
            return new Registration(0, List.of());
        }
        List<LinkOutcome> outcomes;
        try {
            outcomes = links.applyAll(actor, requestId, valid);
        } catch (PartialImportException partial) {
            throw new PartialRegistrationException(partial.created(), partial.failure());
        }
        int created = 0;
        List<Rejection> late = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            int line = valid.get(i).line();
            switch (outcomes.get(i)) {
                case CREATED -> created++;
                case ALREADY_LINKED ->
                        late.add(new Rejection(line, "studentLegacyId", Rejection.STUDENT_ALREADY_LINKED));
                case STUDENT_NOT_ACTIVE -> late.add(new Rejection(line, "studentLegacyId", "student-not-active"));
                case GUARDIAN_NOT_ACTIVE -> late.add(new Rejection(line, "guardianLegacyId", "guardian-not-active"));
            }
        }
        return new Registration(created, late);
    }
}
