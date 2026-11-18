package com.confia.charges.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The void of a charge: who voided it, when, why, and the ledger transaction that reversed it. It is born once and never
 * changes (INV-47): the charge keeps no state, and "voided" is derived from the existence of this record. The request key and
 * its hash make the void idempotent: the same key with the same request finds this record again.
 *
 * @param reversalTransactionId the {@code REVERSAL} transaction of the ledger that mirrors the charge transaction
 * @param requestHash digest of what the request asked, to tell a replay from a different request under the same key
 */
public record ChargeVoid(UUID id, UUID chargeId, UUID reversalTransactionId, String reason, String requestKey,
        String requestHash, UUID voidedBy, Instant voidedAt) {

    public ChargeVoid {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(reversalTransactionId, "reversalTransactionId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(voidedBy, "voidedBy");
        Objects.requireNonNull(voidedAt, "voidedAt");
    }

    /** Whether this void answers the request: the same hash means the same charge and the same reason. */
    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    /** The reason may name a person: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "ChargeVoid[id=" + id + ", chargeId=" + chargeId + "]";
    }
}
