package com.confia.scholarships.application;

import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.DiscountAssignment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A discount assignment with what a reader needs: the discount and the student (by code), the validity, the status and the decisions
 * on it. The list does not load the decisions, so {@code decision} and {@code revocation} are {@code null} there. The reason of a
 * rejection or a revocation may name a person, so {@code toString} shows none of it.
 *
 * @param decision the approval or the rejection, once there is one
 * @param revocation the revocation, once there is one
 */
public record AssignmentView(UUID id, UUID discountId, String discountCode, String discountName, UUID studentId,
        String studentCode, LocalDate validFrom, LocalDate validTo, String status, UUID requestedBy, Instant requestedAt,
        DecisionView decision, DecisionView revocation) {

    /** What was decided: who, when and, for a rejection and a revocation, why. */
    public record DecisionView(UUID id, String kind, UUID decidedBy, Instant decidedAt, String reason) {

        static DecisionView of(AssignmentDecision decision) {
            return decision == null ? null : new DecisionView(decision.id(), decision.kind().code(), decision.decidedBy(),
                    decision.decidedAt(), decision.reason());
        }

        @Override
        public String toString() {
            return "DecisionView[id=" + id + ", kind=" + kind + "]";
        }
    }

    static AssignmentView of(DiscountAssignment assignment, String discountCode, String discountName, String studentCode,
            AssignmentDecision decision, AssignmentDecision revocation) {
        return new AssignmentView(assignment.id(), assignment.discountId(), discountCode, discountName, assignment.studentId(),
                studentCode, assignment.validFrom(), assignment.validTo(), assignment.status().code(), assignment.requestedBy(),
                assignment.requestedAt(), DecisionView.of(decision), DecisionView.of(revocation));
    }

    @Override
    public String toString() {
        return "AssignmentView[id=" + id + ", discountId=" + discountId + ", studentId=" + studentId + ", status=" + status + "]";
    }
}
