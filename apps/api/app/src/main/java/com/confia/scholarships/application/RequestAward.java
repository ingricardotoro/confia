package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.Scholarship;
import com.confia.scholarships.domain.ScholarshipAward;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The request of an award of a scholarship to a student for an academic year, with a validity inside that year (docs/cortes/
 * scholarships-awards.md). It is born {@code pending}: it changes no charge until a person who is not the requester approves it. The
 * scholarship must be active; the student, the scholarship and the academic year must exist in the institution of the actor, and
 * what is out of it looks like what does not exist. That the validity is inside the year is checked by the database, which owns the
 * dates of the year.
 */
public final class RequestAward {

    private final TransactionRunner transactionRunner;
    private final ScholarshipRepository scholarships;
    private final AwardRepository awards;
    private final StudentDirectory students;
    private final AcademicStructureDirectory academicStructure;
    private final AwardViews views;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RequestAward(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students, AcademicStructureDirectory academicStructure,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.scholarships = Objects.requireNonNull(scholarships, "scholarships");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.students = Objects.requireNonNull(students, "students");
        this.academicStructure = Objects.requireNonNull(academicStructure, "academicStructure");
        this.views = new AwardViews(scholarships, decisions, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a request asks: the scholarship, the student, the academic year and the validity, both ends included. */
    public record Command(UUID scholarshipId, UUID studentId, UUID academicYearId, LocalDate validFrom, LocalDate validTo) {
    }

    /**
     * Requests the award.
     *
     * @throws ResourceNotFoundException when the scholarship, the student or the academic year is not in the institution
     * @throws com.confia.scholarships.domain.InactiveReferenceException when the scholarship is inactive
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the validity is wrong or outside the year
     */
    public AwardView request(AuthenticatedActor actor, String requestId, Command command) {
        return ScholarshipRequests.naming(() -> doRequest(actor, requestId, command));
    }

    private AwardView doRequest(AuthenticatedActor actor, String requestId, Command command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Scholarship scholarship = scholarships.findById(institution, command.scholarshipId())
                    .orElseThrow(ResourceNotFoundException::new);
            students.find(institution, command.studentId()).orElseThrow(ResourceNotFoundException::new);
            if (!academicStructure.academicYearExists(institution, command.academicYearId())) {
                throw new ResourceNotFoundException();
            }
            scholarship.requireActive();
            ScholarshipAward award = ScholarshipAward.request(UUID.randomUUID(), scholarship.id(), command.studentId(),
                    command.academicYearId(), command.validFrom(), command.validTo(), actor.accountId(), clock.instant());
            awards.insert(institution, award);
            auditLogWriter.append(ScholarshipsAudit.awardRequested(actor, auditRequestId, award));
            return views.detail(institution, award);
        });
    }
}
