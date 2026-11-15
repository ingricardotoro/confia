package com.confia.guardians.application.importing;

import com.confia.shared.security.AuthenticatedActor;
import java.util.List;

/**
 * Port through which {@code imports} asks {@code guardians} to judge and to register the links of a file. The rows are
 * grouped by student and each group is the complete set of links of that student, judged and registered with the very
 * same rules as the hand replacement of the set (INV-32, the lock of the student, the history, the audit). The import
 * never replaces links that exist. Both operations are scoped to the institution of the actor and nothing here returns
 * or logs a cell value.
 */
public interface GuardianLinkImportGateway {

    /**
     * Judges every row, writing nothing: the cells, the references of the student and the guardian (resolved by the
     * reference of the source system), the rules of the set of each student, whether they are active and whether the
     * student already has current links. A group with any problem is rejected whole. The problems come in line order.
     */
    List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows);

    /**
     * Registers the sets of links of rows that {@link #evaluate} found without problems, one transaction per student,
     * so a failure of one student never touches the others. The answer has one outcome per row, in the order of
     * {@code rows}; every row of a student has the outcome of its set.
     */
    List<LinkOutcome> applyAll(AuthenticatedActor actor, String requestId, List<LinkImportRow> rows);
}
