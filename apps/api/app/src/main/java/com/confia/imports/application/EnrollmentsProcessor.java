package com.confia.imports.application;

import com.confia.imports.domain.CsvTemplate;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Rejection;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.application.importing.EnrollmentImportGateway;
import com.confia.students.application.importing.EnrollmentImportRow;
import com.confia.students.application.importing.EnrollmentOutcome;
import com.confia.students.application.importing.PartialEnrollmentImportException;
import com.confia.students.application.importing.RowProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The enrollments of the current academic year: judged and registered by {@code students} through the port it
 * publishes, with the same use case as the hand enrollment (INV-31, the capacity under the lock of the section, the
 * dates, the audit). The student is named by the reference it has in the source system and the section by the names of
 * its year, modality, grade and section; the import never moves or replaces an enrollment that exists.
 */
final class EnrollmentsProcessor implements ImportProcessor {

    private final EnrollmentImportGateway enrollments;

    EnrollmentsProcessor(EnrollmentImportGateway enrollments) {
        this.enrollments = Objects.requireNonNull(enrollments, "enrollments");
    }

    @Override
    public ImportKind kind() {
        return ImportKind.ENROLLMENTS;
    }

    @Override
    public Judgement judge(AuthenticatedActor actor, String requestId, String text, int maxRows) {
        CsvTemplate.Parsed parsed = ImportKind.ENROLLMENTS.template().parse(text, maxRows);
        List<EnrollmentImportRow> rows = parsed.rows().stream()
                .map(row -> new EnrollmentImportRow(row.line(), row.cell("studentLegacyId"), row.cell("academicYear"),
                        row.cell("modality"), row.cell("grade"), row.cell("section"), row.cell("enrolledOn")))
                .toList();
        List<RowProblem> problems = rows.isEmpty() ? List.of() : enrollments.evaluate(actor, requestId, rows);

        List<Rejection> rejections = new ArrayList<>(parsed.shapeRejections());
        Set<Integer> problemLines = new HashSet<>();
        for (RowProblem problem : problems) {
            rejections.add(new Rejection(problem.line(), problem.field(), problem.reason()));
            problemLines.add(problem.line());
        }
        List<EnrollmentImportRow> valid = rows.stream().filter(row -> !problemLines.contains(row.line())).toList();
        return new Judgement(parsed.totalRows(), rejections, () -> register(actor, requestId, valid));
    }

    private Registration register(AuthenticatedActor actor, String requestId, List<EnrollmentImportRow> valid) {
        if (valid.isEmpty()) {
            return new Registration(0, List.of());
        }
        List<EnrollmentOutcome> outcomes;
        try {
            outcomes = enrollments.applyAll(actor, requestId, valid);
        } catch (PartialEnrollmentImportException partial) {
            throw new PartialRegistrationException(partial.created(), partial.failure());
        }
        int created = 0;
        List<Rejection> late = new ArrayList<>();
        for (int i = 0; i < outcomes.size(); i++) {
            int line = valid.get(i).line();
            switch (outcomes.get(i)) {
                case CREATED -> created++;
                case ALREADY_IMPORTED -> late.add(new Rejection(line, "studentLegacyId", Rejection.ALREADY_IMPORTED));
                case DUPLICATE_ACTIVE_ENROLLMENT ->
                        late.add(new Rejection(line, "studentLegacyId", "duplicate-active-enrollment"));
                case SECTION_FULL -> late.add(new Rejection(line, "section", "section-full"));
                case ACADEMIC_YEAR_CLOSED -> late.add(new Rejection(line, "academicYear", "academic-year-closed"));
                case INACTIVE_SECTION -> late.add(new Rejection(line, "section", "inactive-reference"));
                case STUDENT_NOT_ACTIVE -> late.add(new Rejection(line, "studentLegacyId", "student-not-active"));
                case DATE_OUT_OF_RANGE -> late.add(new Rejection(line, "enrolledOn", "enrollment-date-out-of-range"));
            }
        }
        return new Registration(created, late);
    }
}
