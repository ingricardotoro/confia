package com.confia.payments.application;

import com.confia.charges.application.api.PayableCharges;
import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentDecision;
import com.confia.payments.domain.PaymentStatus;
import com.confia.payments.domain.RejectionReason;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The rejection of a declared transfer by a person authorized who is not the one who registered it (docs/cortes/
 * payments-register.md): the decision is inserted with the reason and the payment moves from {@code declared} to {@code rejected}.
 * A rejected transfer leaves no ledger entry and no allocation, now or ever, and frees its bank and reference. The reason may name
 * a person: it is stored only in the decision and never copied to the ledger or to the audit trail (only its length is).
 *
 * <p>The payment row is locked {@code FOR UPDATE} and read again under the lock, so a rejection and a confirmation of the same
 * transfer take turns and exactly one wins. Nothing here opens the sub-account of the student: nothing is posted.
 */
public final class RejectPayment {

    private final TransactionRunner transactionRunner;
    private final PaymentRepository payments;
    private final ScopedPayments scoped;
    private final PaymentDecisionRepository decisions;
    private final PaymentDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RejectPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, StudentDirectory students,
            PayableCharges charges, PaymentScope scope, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.scoped = new ScopedPayments(payments, scope);
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.details = new PaymentDetails(decisions, allocations, charges, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Rejects the transfer.
     *
     * @param reason why, from 20 to 500 characters
     * @throws com.confia.payments.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws com.confia.payments.domain.InvalidPaymentsValueException when the reason is out of its bounds
     * @throws ResourceNotFoundException when the payment does not exist in the institution of the actor
     * @throws com.confia.payments.domain.PaymentNotDeclaredException when it is not a declared transfer
     * @throws com.confia.payments.domain.SelfApprovalForbiddenException when the actor registered it
     */
    public PaymentDetail reject(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID paymentId,
            String reason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(paymentId, "paymentId");
        UUID auditRequestId = PaymentRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = PaymentRequests.key(idempotencyKey);
        RejectionReason rejection = RejectionReason.of(reason);
        String hash = PaymentRequests.hash("reject", actor.accountId(), paymentId, rejection.text());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, paymentId, rejection, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, paymentId, rejection, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private PaymentDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID paymentId, RejectionReason rejection, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<PaymentDecision> replay = decisions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(actor, institution, replay.get(), hash);
            }
            scoped.find(actor, institution, paymentId).orElseThrow(ResourceNotFoundException::new);
            // Locked and read again: a confirmation that committed while this waited is seen now.
            Payment payment = payments.findForUpdate(institution, paymentId).orElseThrow(ResourceNotFoundException::new);
            Optional<PaymentDecision> underLock = decisions.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(actor, institution, underLock.get(), hash);
            }
            PaymentDecision decision = payment.reject(UUID.randomUUID(), actor.accountId(), rejection, clock.instant(), key,
                    hash);
            decisions.insert(institution, decision);
            payments.advanceStatus(institution, payment.id(), PaymentStatus.REJECTED);
            auditLogWriter.append(PaymentsAudit.rejected(actor, auditRequestId, payment, decision));
            return details.of(institution, payment.decided(decision));
        });
    }

    private PaymentDetail answerReplay(AuthenticatedActor actor, InstitutionId institution, PaymentDecision existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        Payment payment = scoped.find(actor, institution, existing.paymentId()).orElseThrow(ResourceNotFoundException::new);
        return details.of(institution, payment);
    }
}
