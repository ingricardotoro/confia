package com.confia.students.application.importing;

import com.confia.shared.security.AuthenticatedActor;
import java.util.List;

/**
 * Port through which {@code imports} asks {@code students} to judge and to register the enrollments of a file. Every
 * row goes through the very same use case as the hand enrollment (INV-31, the capacity under the lock of the section,
 * the academic year, the dates, the audit): there is no parallel route. Both operations are scoped to the institution of
 * the actor and nothing here returns or logs a cell value. The import never moves or replaces an enrollment that exists.
 */
public interface EnrollmentImportGateway {

    /**
     * Judges every row, writing nothing: the cells, the references of the student and of the section (resolved by the
     * names of its year, modality, grade and section), the rules of the enrollment, the enrollments that exist, and an
     * estimate of the capacity with the seats taken and the rows before it in the file. The problems come in line order.
     */
    List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<EnrollmentImportRow> rows);

    /**
     * Enrolls the rows that {@link #evaluate} found without problems, one transaction per row, in the order of the
     * file, so a rejection never touches the others and the capacity is decided by the real rules under the lock of the
     * section. The answer has one outcome per row, in the order of {@code rows}.
     */
    List<EnrollmentOutcome> applyAll(AuthenticatedActor actor, String requestId, List<EnrollmentImportRow> rows);
}
