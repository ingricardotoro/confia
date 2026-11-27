package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.DiscountDecision;
import java.util.Optional;

/** Output port of the decisions on the campaign discounts: insert-only. The Idempotency-Key of a decision is stored permanently with it. */
public interface DiscountDecisionRepository {

    /**
     * @throws RequestKeyTakenException when the Idempotency-Key was taken by another request
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the database refuses a decision by the requester
     * @throws com.confia.scholarships.domain.InvalidDiscountChangeStateException when the discount does not wait for a decision, or
     *         one was already made for that version
     * @throws com.confia.scholarships.domain.StaleVersionException when the discount is not at the version that was decided
     */
    void insert(InstitutionId institution, DiscountDecision decision);

    Optional<DiscountDecision> findByRequestKey(InstitutionId institution, String requestKey);
}
