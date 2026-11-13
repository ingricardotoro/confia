package com.confia.imports.application;

import com.confia.guardians.application.importing.CreateOutcome;
import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianImportRow;
import com.confia.guardians.application.importing.GuardianLinkImportGateway;
import com.confia.guardians.application.importing.LinkImportRow;
import com.confia.guardians.application.importing.LinkOutcome;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.shared.security.AuthenticatedActor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The ports of the guardians as {@code imports} sees them: they answer what a test programs and record what they get. */
final class FakeGuardianGateways {

    static RowProblem problem(int line, String field, String reason) {
        return new RowProblem(line, field, reason);
    }

    private FakeGuardianGateways() {
    }

    static final class Guardians implements GuardianImportGateway {

        final List<RowProblem> problems = new ArrayList<>();
        final List<GuardianImportRow> evaluated = new ArrayList<>();
        final List<GuardianImportRow> created = new ArrayList<>();
        Function<GuardianImportRow, CreateOutcome> outcomes = row -> CreateOutcome.CREATED;
        RuntimeException failure;

        @Override
        public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<GuardianImportRow> rows) {
            evaluated.addAll(rows);
            return List.copyOf(problems);
        }

        @Override
        public List<CreateOutcome> createAll(AuthenticatedActor actor, String requestId,
                List<GuardianImportRow> rows) {
            if (failure != null) {
                throw failure;
            }
            List<CreateOutcome> result = new ArrayList<>();
            for (GuardianImportRow row : rows) {
                CreateOutcome outcome = outcomes.apply(row);
                if (outcome == CreateOutcome.CREATED) {
                    created.add(row);
                }
                result.add(outcome);
            }
            return result;
        }
    }

    static final class Links implements GuardianLinkImportGateway {

        final List<RowProblem> problems = new ArrayList<>();
        final List<LinkImportRow> evaluated = new ArrayList<>();
        final List<LinkImportRow> applied = new ArrayList<>();
        Function<LinkImportRow, LinkOutcome> outcomes = row -> LinkOutcome.CREATED;
        RuntimeException failure;

        @Override
        public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows) {
            evaluated.addAll(rows);
            return List.copyOf(problems);
        }

        @Override
        public List<LinkOutcome> applyAll(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows) {
            if (failure != null) {
                throw failure;
            }
            List<LinkOutcome> result = new ArrayList<>();
            for (LinkImportRow row : rows) {
                LinkOutcome outcome = outcomes.apply(row);
                if (outcome == LinkOutcome.CREATED) {
                    applied.add(row);
                }
                result.add(outcome);
            }
            return result;
        }
    }
}
