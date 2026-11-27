package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.DiscountAssignment;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The decisions on a discount assignment (docs/cortes/scholarships-discounts.md): a person authorized who is not the one who
 * requested it approves or rejects it (segregation of duties), and a person authorized revokes an approved one. Each is ONE database
 * transaction: the decision is inserted with the reason (rejection and revocation) and the assignment moves to its new status. An
 * approval, a rejection and a revocation change no charge that was already issued: the engine applies an assignment only to the
 * charges issued afterwards. The reason may name a person: it is stored only in the decision and never copied to the audit trail
 * (only its SHA-256 and its length are).
 *
 * <p>Order, as for the awards: (1) the key of the request is looked up; (2) the assignment is read, so what is not in the institution
 * of the actor is absent; (3) the assignment row is locked {@code FOR UPDATE} and read again, so two decisions on the same
 * assignment take turns and exactly one wins; (4) the key is looked up again under the lock; (5) the rules of the domain; (6) the
 * decision and the new status are written, and the database refuses an approval that would overlap another approved assignment of
 * the discount for the student; (7) the audit entry is written.
 *
 * <p>The {@code Idempotency-Key} is stored permanently on the decision with the hash of what it asked, which includes the actor.
 */
public final class DecideDiscountAssignment {

    private final TransactionRunner transactionRunner;
    private final DiscountAssignmentRepository assignments;
    private final AssignmentDecisionRepository decisions;
    private final DiscountRepository discounts;
    private final AssignmentViews views;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DecideDiscountAssignment(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountAssignmentRepository assignments, AssignmentDecisionRepository decisions, StudentDirectory students,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.views = new AssignmentViews(discounts, decisions, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the domain does to the assignment: makes the decision. */
    private interface Deciding {

        AssignmentDecision on(DiscountAssignment assignment, UUID decisionId, Instant now, String key, String hash);
    }

    /**
     * Approves the assignment.
     *
     * @throws com.confia.scholarships.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws ResourceNotFoundException when the assignment is not in the institution of the actor
     * @throws com.confia.scholarships.domain.InvalidAssignmentStateException when it is not pending
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the actor requested it
     * @throws com.confia.scholarships.domain.InactiveReferenceException when its discount is inactive
     * @throws com.confia.scholarships.domain.DiscountAssignmentOverlapException when the student has another approved assignment of
     *         the discount in those days
     */
    public AssignmentView approve(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID assignmentId) {
        Objects.requireNonNull(assignmentId, "assignmentId");
        return decide(actor, requestId, idempotencyKey, assignmentId, null, "approve-discount-assignment",
                (assignment, id, now, key, hash) -> assignment.approve(id, actor.accountId(), now, key, hash));
    }

    /**
     * Rejects the assignment with the reason, from 20 to 500 characters.
     *
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the reason is out of its bounds
     * @throws com.confia.scholarships.domain.InvalidAssignmentStateException when it is not pending
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the actor requested it
     */
    public AssignmentView reject(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID assignmentId,
            String reason) {
        Objects.requireNonNull(assignmentId, "assignmentId");
        return ScholarshipRequests.naming(() -> {
            DecisionReason because = DecisionReason.of(reason);
            return decide(actor, requestId, idempotencyKey, assignmentId, because, "reject-discount-assignment",
                    (assignment, id, now, key, hash) -> assignment.reject(id, actor.accountId(), because, now, key, hash));
        });
    }

    /**
     * Revokes the approved assignment with the reason, from 20 to 500 characters. It affects only the charges issued afterwards.
     *
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the reason is out of its bounds
     * @throws com.confia.scholarships.domain.InvalidAssignmentStateException when it is not approved
     */
    public AssignmentView revoke(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID assignmentId,
            String reason) {
        Objects.requireNonNull(assignmentId, "assignmentId");
        return ScholarshipRequests.naming(() -> {
            DecisionReason because = DecisionReason.of(reason);
            return decide(actor, requestId, idempotencyKey, assignmentId, because, "revoke-discount-assignment",
                    (assignment, id, now, key, hash) -> assignment.revoke(id, actor.accountId(), because, now, key, hash));
        });
    }

    private AssignmentView decide(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID assignmentId,
            DecisionReason reason, String operation, Deciding deciding) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = ScholarshipRequests.key(idempotencyKey);
        String hash = reason == null ? ScholarshipRequests.hash(operation, actor.accountId(), assignmentId)
                : ScholarshipRequests.hash(operation, actor.accountId(), assignmentId, reason.text());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, assignmentId, reason, hash, deciding);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, assignmentId, reason, hash, deciding);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private AssignmentView attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID assignmentId, DecisionReason reason, String hash, Deciding deciding) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<AssignmentDecision> replay = decisions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            assignments.findById(institution, assignmentId).orElseThrow(ResourceNotFoundException::new);
            // Locked and read again: a decision that committed while this waited is seen now.
            DiscountAssignment assignment = assignments.findForUpdate(institution, assignmentId)
                    .orElseThrow(ResourceNotFoundException::new);
            Optional<AssignmentDecision> underLock = decisions.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            AssignmentDecision decision = deciding.on(assignment, UUID.randomUUID(), clock.instant(), key, hash);
            if (decision.kind() == DecisionKind.APPROVED) {
                discounts.findById(institution, assignment.discountId()).orElseThrow(ResourceNotFoundException::new)
                        .requireActive();
            }
            decisions.insert(institution, decision);
            assignments.advanceStatus(institution, assignment.id(), decision.kind().leadsTo());
            auditLogWriter.append(DiscountsAudit.assignmentDecided(actor, auditRequestId, assignment, decision, reason));
            return views.detail(institution, assignment.decided(decision));
        });
    }

    private AssignmentView answerReplay(InstitutionId institution, AssignmentDecision existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        DiscountAssignment assignment = assignments.findById(institution, existing.assignmentId())
                .orElseThrow(ResourceNotFoundException::new);
        return views.detail(institution, assignment);
    }
}
