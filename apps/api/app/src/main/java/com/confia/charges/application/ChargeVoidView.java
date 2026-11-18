package com.confia.charges.application;

import com.confia.charges.domain.ChargeStatus;
import com.confia.charges.domain.ChargeVoid;
import java.time.Instant;
import java.util.UUID;

/** A void as the web layer may see it. The request key and its hash stay inside. */
public record ChargeVoidView(UUID id, UUID chargeId, UUID reversalTransactionId, String reason, UUID voidedBy,
        Instant voidedAt) {

    /** What a charge is once a void exists: the derived status of the charge. */
    public String status() {
        return ChargeStatus.VOIDED.name();
    }

    static ChargeVoidView of(ChargeVoid charge) {
        return new ChargeVoidView(charge.id(), charge.chargeId(), charge.reversalTransactionId(), charge.reason(),
                charge.voidedBy(), charge.voidedAt());
    }
}
