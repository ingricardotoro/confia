package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.util.Objects;
import java.util.UUID;

/**
 * Lists and reads the discount assignments of the institution of the actor (docs/cortes/scholarships-discounts.md): a page filtered
 * by student, status and discount, paginated and sorted by the database with a limit applied by the server, and one assignment with
 * its decisions. An assignment of another institution looks like one that does not exist.
 */
public final class ViewDiscountAssignments {

    private final TransactionRunner transactionRunner;
    private final DiscountAssignmentRepository assignments;
    private final AssignmentViews views;

    public ViewDiscountAssignments(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.views = new AssignmentViews(discounts, decisions, students);
    }

    public Page<AssignmentView> list(AuthenticatedActor actor, String requestId, AssignmentFilter filter, PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.status() != null && AwardStatus.ofCode(filter.status()).isEmpty()) {
            throw new InvalidScholarshipsValueException();
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ScholarshipRequests.requestId(requestId).toString()),
                () -> views.page(institution, assignments.list(institution, filter, page)));
    }

    public AssignmentView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ScholarshipRequests.requestId(requestId).toString()),
                () -> views.detail(institution,
                        assignments.findById(institution, id).orElseThrow(ResourceNotFoundException::new)));
    }
}
