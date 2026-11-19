package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashMovement;
import com.confia.kernel.InstitutionId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Output port of the cash movements. It inserts and reads: no operation updates or deletes a movement (INV-21). */
public interface CashMovementRepository {

    /** @throws RequestKeyTakenException when the Idempotency-Key was taken by another request */
    void insert(InstitutionId institution, CashMovement movement);

    Optional<CashMovement> findByRequestKey(InstitutionId institution, String requestKey);

    /** The movements of the session, oldest first. */
    List<CashMovement> findBySession(InstitutionId institution, UUID sessionId);
}
