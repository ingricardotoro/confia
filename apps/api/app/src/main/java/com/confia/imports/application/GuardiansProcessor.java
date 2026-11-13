package com.confia.imports.application;

import com.confia.guardians.application.importing.CreateOutcome;
import com.confia.guardians.application.importing.GuardianImportGateway;
import com.confia.guardians.application.importing.GuardianImportRow;
import com.confia.guardians.application.importing.PartialImportException;
import com.confia.guardians.application.importing.RowProblem;
import com.confia.imports.domain.CsvTemplate;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Rejection;
import com.confia.shared.security.AuthenticatedActor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The guardians: judged and registered by {@code guardians} through the port it publishes, with the same use case as
 * the hand registration (code, encrypted document and RTN, audit). Their national identity document is only compared
 * with the stored ones when a row is registered.
 */
final class GuardiansProcessor implements ImportProcessor {

    private final GuardianImportGateway guardians;

    GuardiansProcessor(GuardianImportGateway guardians) {
        this.guardians = Objects.requireNonNull(guardians, "guardians");
    }

    @Override
    public ImportKind kind() {
        return ImportKind.GUARDIANS;
    }

    @Override
    public Judgement judge(AuthenticatedActor actor, String requestId, String text, int maxRows) {
        CsvTemplate.Parsed parsed = ImportKind.GUARDIANS.template().parse(text, maxRows);
        List<GuardianImportRow> rows = parsed.rows().stream()
                .map(row -> new GuardianImportRow(row.line(), row.cell("legacyId"), row.cell("firstName"),
                        row.cell("lastName"), row.cell("nationalId"), row.cell("rtn"), row.cell("email"),
                        row.cell("phone"), row.cell("address"), row.cell("preferredChannel")))
                .toList();
        List<RowProblem> problems = rows.isEmpty() ? List.of() : guardians.evaluate(actor, requestId, rows);

        List<Rejection> rejections = new ArrayList<>(parsed.shapeRejections());
        Set<Integer> problemLines = new HashSet<>();
        for (RowProblem problem : problems) {
            rejections.add(new Rejection(problem.line(), problem.field(), problem.reason()));
            problemLines.add(problem.line());
        }
        List<GuardianImportRow> valid = rows.stream().filter(row -> !problemLines.contains(row.line())).toList();
        return new Judgement(parsed.totalRows(), rejections, () -> register(actor, requestId, valid));
    }

    private Registration register(AuthenticatedActor actor, String requestId, List<GuardianImportRow> valid) {
        if (valid.isEmpty()) {
            return new Registration(0, List.of());
        }
        List<CreateOutcome> outcomes;
        try {
            outcomes = guardians.createAll(actor, requestId, valid);
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
