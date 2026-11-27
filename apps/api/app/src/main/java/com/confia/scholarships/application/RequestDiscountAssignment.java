package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountAssignment;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The request of the assignment of an agreement discount to a student, with a validity inside the one of the discount (docs/cortes/
 * scholarships-discounts.md). It is born {@code pending}: it changes no charge until a person who is not the requester approves it.
 * The discount must be of agreement kind and active; the student and the discount must exist in the institution of the actor, and
 * what is out of it looks like what does not exist. The validity is checked by the domain and again by the database, which owns the
 * dates of the discount.
 */
public final class RequestDiscountAssignment {

    private final TransactionRunner transactionRunner;
    private final DiscountRepository discounts;
    private final DiscountAssignmentRepository assignments;
    private final StudentDirectory students;
    private final AssignmentViews views;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RequestDiscountAssignment(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.students = Objects.requireNonNull(students, "students");
        this.views = new AssignmentViews(discounts, decisions, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What a request asks: the discount, the student and the validity, both ends included. */
    public record Command(UUID discountId, UUID studentId, LocalDate validFrom, LocalDate validTo) {
    }

    /**
     * Requests the assignment.
     *
     * @throws ResourceNotFoundException when the discount or the student is not in the institution
     * @throws com.confia.scholarships.domain.InactiveReferenceException when the discount is inactive
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the discount is not of agreement kind or the
     *         validity is wrong or outside the one of the discount
     */
    public AssignmentView request(AuthenticatedActor actor, String requestId, Command command) {
        return ScholarshipRequests.naming(() -> doRequest(actor, requestId, command));
    }

    private AssignmentView doRequest(AuthenticatedActor actor, String requestId, Command command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Discount discount = discounts.findById(institution, command.discountId()).orElseThrow(ResourceNotFoundException::new);
            students.find(institution, command.studentId()).orElseThrow(ResourceNotFoundException::new);
            DiscountAssignment assignment = DiscountAssignment.request(UUID.randomUUID(), discount, command.studentId(),
                    command.validFrom(), command.validTo(), actor.accountId(), clock.instant());
            assignments.insert(institution, assignment);
            auditLogWriter.append(DiscountsAudit.assignmentRequested(actor, auditRequestId, assignment));
            return views.detail(institution, assignment);
        });
    }
}
