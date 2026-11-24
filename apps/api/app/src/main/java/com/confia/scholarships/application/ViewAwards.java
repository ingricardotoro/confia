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
 * Lists and reads the awards of the institution of the actor (docs/cortes/scholarships-awards.md): a page filtered by student,
 * status, scholarship and academic year, paginated and sorted by the database with a limit applied by the server, and one award with
 * its decisions. An award of another institution looks like one that does not exist.
 */
public final class ViewAwards {

    private final TransactionRunner transactionRunner;
    private final AwardRepository awards;
    private final AwardViews views;

    public ViewAwards(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.views = new AwardViews(scholarships, decisions, students);
    }

    public Page<AwardView> list(AuthenticatedActor actor, String requestId, AwardFilter filter, PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.status() != null && AwardStatus.ofCode(filter.status()).isEmpty()) {
            throw new InvalidScholarshipsValueException();
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ScholarshipRequests.requestId(requestId).toString()),
                () -> views.page(institution, awards.list(institution, filter, page)));
    }

    public AwardView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(id, "id");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ScholarshipRequests.requestId(requestId).toString()),
                () -> views.detail(institution, awards.findById(institution, id).orElseThrow(ResourceNotFoundException::new)));
    }
}
