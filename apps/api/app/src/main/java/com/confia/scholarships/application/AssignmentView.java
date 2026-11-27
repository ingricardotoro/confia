package com.confia.scholarships.application;

import com.confia.kernel.Money;
import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.DiscountAssignment;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A discount assignment with what a reader needs: the discount and the student (by code), the validity, the status and the decisions
 * on it. The list does not load the decisions, so {@code decision} and {@code revocation} are {@code null} there. The reason of a
 * rejection or a revocation may name a person, so {@code toString} shows none of it.
 *
 * @param decision the approval or the rejection, once there is one
 * @param discountVersion the version of the discount when the assignment was requested, the terms the approval is bound to; {@code
 *     null} for an assignment from before it was kept (it can no longer be approved)
 * @param benefitType the type of the benefit of the discount at the request
 * @param percent the percentage points of that benefit, with two decimals, only for a percentage
 * @param fixedAmount the fixed amount of that benefit, only for a fixed amount
 * @param revocation the revocation, once there is one
 */
public record AssignmentView(UUID id, UUID discountId, String discountCode, String discountName, UUID studentId,
        String studentCode, LocalDate validFrom, LocalDate validTo, String status, UUID requestedBy, Instant requestedAt,
        DecisionView decision, DecisionView revocation, Long discountVersion, String benefitType, String percent,
        Money fixedAmount) {

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
                assignment.requestedAt(), DecisionView.of(decision), DecisionView.of(revocation), assignment.discountVersion(),
                assignment.benefit() == null ? null : assignment.benefit().type().name(), percentOf(assignment.benefit()),
                assignment.benefit() == null ? null : assignment.benefit().fixedAmount());
    }

    private static String percentOf(Benefit benefit) {
        return benefit == null || benefit.percent() == null ? null
                : benefit.percent().value().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    @Override
    public String toString() {
        return "AssignmentView[id=" + id + ", discountId=" + discountId + ", studentId=" + studentId + ", status=" + status + "]";
    }
}
