package com.confia.imports.application;

import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Rejection;
import com.confia.imports.domain.StudentsCsv;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.application.importing.CreateOutcome;
import com.confia.students.application.importing.ImportRow;
import com.confia.students.application.importing.PartialImportException;
import com.confia.students.application.importing.RowProblem;
import com.confia.students.application.importing.StudentImportGateway;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The students: judged and registered by {@code students} through the port it publishes. */
final class StudentsProcessor implements ImportProcessor {

    private final StudentImportGateway students;

    StudentsProcessor(StudentImportGateway students) {
        this.students = Objects.requireNonNull(students, "students");
    }

    @Override
    public ImportKind kind() {
        return ImportKind.STUDENTS;
    }

    @Override
    public Judgement judge(AuthenticatedActor actor, String requestId, String text, int maxRows) {
        StudentsCsv.Parsed parsed = StudentsCsv.parse(text, maxRows);
        List<ImportRow> rows = parsed.rows().stream().map(row -> new ImportRow(row.line(), row.legacyId(),
                row.firstName(), row.lastName(), row.birthDate(), row.sex(), row.nationalId())).toList();
        List<RowProblem> problems = rows.isEmpty() ? List.of() : students.evaluate(actor, requestId, rows);

        List<Rejection> rejections = new ArrayList<>(parsed.shapeRejections());
        Set<Integer> problemLines = new HashSet<>();
        for (RowProblem problem : problems) {
            rejections.add(new Rejection(problem.line(), problem.field(), problem.reason()));
            problemLines.add(problem.line());
        }
        List<ImportRow> valid = rows.stream().filter(row -> !problemLines.contains(row.line())).toList();
        return new Judgement(parsed.totalRows(), rejections, () -> register(actor, requestId, valid));
    }

    private Registration register(AuthenticatedActor actor, String requestId, List<ImportRow> valid) {
        if (valid.isEmpty()) {
            return new Registration(0, List.of());
        }
        List<CreateOutcome> outcomes;
        try {
            outcomes = students.createAll(actor, requestId, valid);
        } catch (PartialImportException partial) {
            throw new PartialRegistrationException(partial.created(), partial.failure());
        }
        int created = 0;
        List<Rejection> late = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            int line = valid.get(i).line();
            switch (outcomes.get(i)) {
                case CREATED -> created++;
                case ALREADY_IMPORTED -> late.add(new Rejection(line, "legacyId", Rejection.ALREADY_IMPORTED));
                case DUPLICATE_NATIONAL_ID -> late.add(new Rejection(line, "nationalId", "duplicate-national-id"));
            }
        }
        return new Registration(created, late);
    }
}
