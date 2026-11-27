package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AssignmentDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port of the decisions on the discount assignments: insert-only. The Idempotency-Key of a decision is stored permanently with it. */
public interface AssignmentDecisionRepository {

    /**
     * @throws RequestKeyTakenException when the Idempotency-Key was taken by another request
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the database refuses a decision by the requester
     * @throws com.confia.scholarships.domain.InvalidAssignmentStateException when the assignment is not in the state the decision
     *         acts on
     */
    void insert(InstitutionId institution, AssignmentDecision decision);

    Optional<AssignmentDecision> findByRequestKey(InstitutionId institution, String requestKey);

    /** The decisions on the assignment, oldest first: the approval or the rejection, and then the revocation. */
    List<AssignmentDecision> findByAssignment(InstitutionId institution, UUID assignmentId);
}
