package com.confia.charges.application;

import com.confia.charges.domain.ChargeVoid;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the voids of charges. It inserts and reads: no operation updates or deletes a void, and the database refuses
 * them to every role (INV-47). The void is inserted AFTER the reversal in the ledger, in the same database transaction.
 */
public interface ChargeVoidRepository {

    void insert(InstitutionId institution, ChargeVoid charge);

    Optional<ChargeVoid> findByRequestKey(InstitutionId institution, String requestKey);

    Optional<ChargeVoid> findByChargeId(InstitutionId institution, UUID chargeId);

    /** Another void took the request key a moment ago: the caller starts again and finds it by key. */
    final class RequestKeyTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RequestKeyTakenException() {
            super("the request key is already in use", null, false, false);
        }
    }
}
