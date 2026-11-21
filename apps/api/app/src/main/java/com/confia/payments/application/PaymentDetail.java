package com.confia.payments.application;

import com.confia.kernel.Money;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment with everything a reader needs: its state, the decision on it, the charges it was applied to and what stayed as a
 * credit. The amounts are exact; the web layer rounds them for presentation. The notes, the reference and the reason of a
 * rejection may identify a person or a bank operation, so {@code toString} shows none of them.
 */
public record PaymentDetail(UUID id, UUID studentId, String studentCode, UUID guardianId, String method, String status,
        Money amount, LocalDate receivedOn, String reference, String bankName, String notes, UUID cashSessionId,
        UUID ledgerTransactionId, UUID registeredBy, Instant registeredAt, Decision decision,
        List<AllocationView> allocations, Money allocated, Money unallocated) {

    public PaymentDetail {
        allocations = List.copyOf(allocations);
    }

    /** What was decided about a declared transfer. The reason only exists for a rejection. */
    public record Decision(UUID id, String kind, UUID decidedBy, Instant decidedAt, String reason) {

        static Decision of(PaymentDecision decision) {
            return decision == null ? null : new Decision(decision.id(), decision.kind().code(), decision.decidedBy(),
                    decision.decidedAt(), decision.reason());
        }

        @Override
        public String toString() {
            return "Decision[id=" + id + ", kind=" + kind + "]";
        }
    }

    /** The part of the payment applied to one charge, with what identifies the charge. */
    public record AllocationView(UUID chargeId, String conceptCode, String conceptName, String periodLabel,
            LocalDate dueOn, Money amount, Instant allocatedAt) {
    }

    static String statusOf(Payment payment) {
        return payment.status().code();
    }

    @Override
    public String toString() {
        return "PaymentDetail[id=" + id + ", studentId=" + studentId + ", method=" + method + ", status=" + status + "]";
    }
}
