package com.confia.imports.application;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.students.application.importing.EnrollmentImportGateway;
import com.confia.students.application.importing.EnrollmentImportRow;
import com.confia.students.application.importing.EnrollmentOutcome;
import com.confia.students.application.importing.RowProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The port of the enrollments as {@code imports} sees it: it answers what a test programs and records what it gets. */
final class FakeEnrollmentGateway implements EnrollmentImportGateway {

    final List<RowProblem> problems = new ArrayList<>();
    final List<EnrollmentImportRow> evaluated = new ArrayList<>();
    final List<EnrollmentImportRow> applied = new ArrayList<>();
    Function<EnrollmentImportRow, EnrollmentOutcome> outcomes = row -> EnrollmentOutcome.CREATED;
    RuntimeException failure;

    @Override
    public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<EnrollmentImportRow> rows) {
        evaluated.addAll(rows);
        return List.copyOf(problems);
    }

    @Override
    public List<EnrollmentOutcome> applyAll(AuthenticatedActor actor, String requestId,
            List<EnrollmentImportRow> rows) {
        if (failure != null) {
            throw failure;
        }
        List<EnrollmentOutcome> result = new ArrayList<>();
        for (EnrollmentImportRow row : rows) {
            EnrollmentOutcome outcome = outcomes.apply(row);
            if (outcome == EnrollmentOutcome.CREATED) {
                applied.add(row);
            }
            result.add(outcome);
        }
        return result;
    }
}
