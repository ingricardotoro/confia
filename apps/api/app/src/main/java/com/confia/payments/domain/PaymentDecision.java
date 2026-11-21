package com.confia.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The confirmation or the rejection of a declared transfer: who, when, and the posting (a confirmation) or the reason (a
 * rejection). Born once and never changes: the status of the payment moves only together with this record. The key and its hash
 * make the request idempotent. The reason may name a person, so {@code toString} does not show it.
 *
 * @param reason only a rejection has one
 * @param ledgerTransactionId only a confirmation has one: the {@code PAYMENT} transaction that posted the transfer
 */
public record PaymentDecision(UUID id, UUID paymentId, DecisionKind kind, String reason, UUID ledgerTransactionId,
        UUID decidedBy, Instant decidedAt, String requestKey, String requestHash) {

    public PaymentDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        boolean confirmed = kind == DecisionKind.CONFIRMED;
        if (confirmed != (ledgerTransactionId != null) || confirmed != (reason == null)) {
            throw new InvalidPaymentsValueException();
        }
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "PaymentDecision[id=" + id + ", paymentId=" + paymentId + ", kind=" + kind + "]";
    }
}
