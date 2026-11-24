package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port of the decisions on the awards: insert-only. The Idempotency-Key of a decision is stored permanently with it. */
public interface AwardDecisionRepository {

    /**
     * @throws RequestKeyTakenException when the Idempotency-Key was taken by another request
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the database refuses a decision by the requester
     * @throws com.confia.scholarships.domain.InvalidAwardStateException when the award is not in the state the decision acts on
     */
    void insert(InstitutionId institution, AwardDecision decision);

    Optional<AwardDecision> findByRequestKey(InstitutionId institution, String requestKey);

    /** The decisions on the award, oldest first: the approval or the rejection, and then the revocation. */
    List<AwardDecision> findByAward(InstitutionId institution, UUID awardId);
}
