package com.confia.payments.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.PaymentDecision;
import java.util.Optional;
import java.util.UUID;

/** Output port of the decisions on declared transfers. It inserts and reads: nothing is updated or deleted. */
public interface PaymentDecisionRepository {

    /** @throws RequestKeyTakenException when the Idempotency-Key was taken by another request */
    void insert(InstitutionId institution, PaymentDecision decision);

    Optional<PaymentDecision> findByRequestKey(InstitutionId institution, String requestKey);

    Optional<PaymentDecision> findByPayment(InstitutionId institution, UUID paymentId);
}
