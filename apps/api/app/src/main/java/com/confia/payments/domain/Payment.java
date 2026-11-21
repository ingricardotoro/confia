package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment of a student (docs/cortes/payments-register.md; INV-17 to INV-20, INV-24). Cash is born {@code confirmed}, with the
 * session of the cashier, the {@code payment_in} movement it left there and the posting in the ledger; a bank transfer is born
 * {@code declared}, outside the ledger and outside the balance, and is decided once by a person who is not the one who registered
 * it: confirmed (and then posted and allocated) or rejected (and then it leaves no entry). It never changes after that and it is
 * never deleted: the only thing that moves is its status, with the {@link PaymentDecision} that justifies it.
 *
 * <p>The key and its hash make the registration idempotent: the same key with the same request finds this payment again. The
 * notes and the reference may name a person or a bank account, so {@code toString} shows neither.
 *
 * @param ledgerTransactionId the posting of a cash payment; a transfer's posting lives in its decision
 */
public record Payment(UUID id, UUID studentId, UUID guardianId, PaymentMethod method, PaymentStatus status, Money amount,
        LocalDate receivedOn, TransferReference transfer, String notes, UUID cashSessionId, UUID cashMovementId,
        UUID ledgerTransactionId, UUID registeredBy, Instant registeredAt, String requestKey, String requestHash) {

    public Payment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receivedOn, "receivedOn");
        Objects.requireNonNull(registeredBy, "registeredBy");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    /**
     * Registers cash already collected: the open session, the movement it left and the ledger posting exist when this is built,
     * and the payment is born confirmed.
     */
    public static Payment cash(UUID id, PaymentRequest request, UUID registeredBy, Instant now, String requestKey,
            String requestHash, UUID cashSessionId, UUID cashMovementId, UUID ledgerTransactionId) {
        if (request.method() != PaymentMethod.CASH) {
            throw new InvalidPaymentsValueException();
        }
        return new Payment(id, request.studentId(), request.guardianId(), PaymentMethod.CASH, PaymentStatus.CONFIRMED,
                request.amount(), request.receivedOn(), null, request.notes(),
                Objects.requireNonNull(cashSessionId, "cashSessionId"),
                Objects.requireNonNull(cashMovementId, "cashMovementId"),
                Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId"), registeredBy, now, requestKey,
                requestHash);
    }

    /** Declares a bank transfer: it touches no cash and no ledger, and waits for a person authorized. */
    public static Payment declaredTransfer(UUID id, PaymentRequest request, UUID registeredBy, Instant now,
            String requestKey, String requestHash) {
        if (request.method() != PaymentMethod.BANK_TRANSFER) {
            throw new InvalidPaymentsValueException();
        }
        return new Payment(id, request.studentId(), request.guardianId(), PaymentMethod.BANK_TRANSFER,
                PaymentStatus.DECLARED, request.amount(), request.receivedOn(), request.transfer(), request.notes(), null,
                null, null, registeredBy, now, requestKey, requestHash);
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    public boolean isDeclared() {
        return status == PaymentStatus.DECLARED;
    }

    /**
     * The confirmation of this declared transfer by {@code by}, with the ledger transaction that posted it.
     *
     * @throws PaymentNotDeclaredException when the payment is not a declared transfer
     * @throws SelfApprovalForbiddenException when {@code by} registered the payment
     */
    public PaymentDecision confirm(UUID decisionId, UUID by, UUID ledgerTransactionId, Instant now, String requestKey,
            String requestHash) {
        requireDecidableBy(by);
        return new PaymentDecision(decisionId, id, DecisionKind.CONFIRMED, null,
                Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId"), by, now, requestKey, requestHash);
    }

    /**
     * The rejection of this declared transfer by {@code by}, with the reason. It leaves no ledger entry.
     *
     * @throws PaymentNotDeclaredException when the payment is not a declared transfer
     * @throws SelfApprovalForbiddenException when {@code by} registered the payment
     */
    public PaymentDecision reject(UUID decisionId, UUID by, RejectionReason reason, Instant now, String requestKey,
            String requestHash) {
        requireDecidableBy(by);
        return new PaymentDecision(decisionId, id, DecisionKind.REJECTED, reason.text(), null, by, now, requestKey,
                requestHash);
    }

    /** This payment once the decision is recorded. */
    public Payment decided(PaymentDecision decision) {
        return new Payment(id, studentId, guardianId, method, decision.kind().resultingStatus(), amount, receivedOn,
                transfer, notes, cashSessionId, cashMovementId, ledgerTransactionId, registeredBy, registeredAt,
                requestKey, requestHash);
    }

    /**
     * Checks that {@code by} may decide this payment now, before anything is written.
     *
     * @throws PaymentNotDeclaredException when the payment is not a declared transfer
     * @throws SelfApprovalForbiddenException when {@code by} registered the payment
     */
    public void requireDecidableBy(UUID by) {
        Objects.requireNonNull(by, "by");
        if (method != PaymentMethod.BANK_TRANSFER || status != PaymentStatus.DECLARED) {
            throw new PaymentNotDeclaredException();
        }
        if (registeredBy.equals(by)) {
            throw new SelfApprovalForbiddenException();
        }
    }

    @Override
    public String toString() {
        return "Payment[id=" + id + ", studentId=" + studentId + ", method=" + method + ", status=" + status + "]";
    }
}
