package com.confia.scholarships.application;

import com.confia.scholarships.domain.AwardDecision;
import com.confia.scholarships.domain.ScholarshipAward;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An award with what a reader needs: the scholarship and the student (by code), the validity, the status and the decisions on it.
 * The list does not load the decisions, so {@code decision} and {@code revocation} are {@code null} there. The reason of a rejection
 * or a revocation may name a person, so {@code toString} shows none of it.
 *
 * @param decision the approval or the rejection, once there is one
 * @param revocation the revocation, once there is one
 */
public record AwardView(UUID id, UUID scholarshipId, String scholarshipCode, String scholarshipName, UUID studentId,
        String studentCode, UUID academicYearId, LocalDate validFrom, LocalDate validTo, String status, UUID requestedBy,
        Instant requestedAt, DecisionView decision, DecisionView revocation) {

    /** What was decided: who, when and, for a rejection and a revocation, why. */
    public record DecisionView(UUID id, String kind, UUID decidedBy, Instant decidedAt, String reason) {

        static DecisionView of(AwardDecision decision) {
            return decision == null ? null : new DecisionView(decision.id(), decision.kind().code(), decision.decidedBy(),
                    decision.decidedAt(), decision.reason());
        }

        @Override
        public String toString() {
            return "DecisionView[id=" + id + ", kind=" + kind + "]";
        }
    }

    static AwardView of(ScholarshipAward award, String scholarshipCode, String scholarshipName, String studentCode,
            AwardDecision decision, AwardDecision revocation) {
        return new AwardView(award.id(), award.scholarshipId(), scholarshipCode, scholarshipName, award.studentId(),
                studentCode, award.academicYearId(), award.validFrom(), award.validTo(), award.status().code(),
                award.requestedBy(), award.requestedAt(), DecisionView.of(decision), DecisionView.of(revocation));
    }

    @Override
    public String toString() {
        return "AwardView[id=" + id + ", scholarshipId=" + scholarshipId + ", studentId=" + studentId + ", status=" + status + "]";
    }
}
