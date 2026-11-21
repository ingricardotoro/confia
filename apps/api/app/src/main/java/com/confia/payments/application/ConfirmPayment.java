package com.confia.payments.application;

import com.confia.charges.application.api.PayableCharges;
import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.payments.domain.AllocationPlan;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentDecision;
import com.confia.payments.domain.PaymentMethod;
import com.confia.payments.domain.PaymentStatus;
import com.confia.payments.domain.PostingAccounts;
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
 * The confirmation of a declared transfer by a person authorized who is not the one who registered it (docs/cortes/
 * payments-register.md), in ONE database transaction: the ledger posts the payment (debit the bank, credit the receivable of the
 * student), the decision is inserted, the payment moves from {@code declared} to {@code confirmed} and the amount is allocated to
 * the charges that are active at this moment (a charge voided since the transfer was declared is not offered).
 *
 * <p>Order, the lock order of the financial flow: (1) the decision key is looked up; (2) the payment is read; (3) the posting
 * accounts are proven; (4) the sub-account of the student is locked {@code FOR UPDATE}; (5) under that lock the key is read again
 * and the payment row is locked {@code FOR UPDATE} and read again, so a confirmation and a rejection of the same transfer take
 * turns and exactly one wins; (6) the open charges are read and the plan computed; (7) the ledger posts; (8) the decision, the
 * new status and the allocations are written; (9) the audit entry is written.
 *
 * <p>The {@code Idempotency-Key} is stored permanently on the decision with the hash of what it asked, which includes the actor.
 */
public final class ConfirmPayment {

    private final TransactionRunner transactionRunner;
    private final PaymentRepository payments;
    private final ScopedPayments scoped;
    private final PaymentDecisionRepository decisions;
    private final PaymentPoster poster;
    private final PaymentDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ConfirmPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, PostingAccountRepository map,
            StudentDirectory students, LedgerPostingApi ledger, LedgerAccountDirectory ledgerAccounts,
            PayableCharges charges, PaymentScope scope, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.scoped = new ScopedPayments(payments, scope);
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.poster = new PaymentPoster(ledger, ledgerAccounts, charges, map, allocations, clock);
        this.details = new PaymentDetails(decisions, allocations, charges, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Confirms the transfer.
     *
     * @throws com.confia.payments.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws ResourceNotFoundException when the payment does not exist in the institution of the actor
     * @throws com.confia.payments.domain.PaymentNotDeclaredException when it is not a declared transfer (also a second confirmation)
     * @throws com.confia.payments.domain.SelfApprovalForbiddenException when the actor registered it
     * @throws com.confia.payments.domain.PostingAccountsIncompleteException when a posting account is not set or usable
     */
    public PaymentDetail confirm(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID paymentId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(paymentId, "paymentId");
        UUID auditRequestId = PaymentRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = PaymentRequests.key(idempotencyKey);
        String hash = PaymentRequests.hash("confirm", actor.accountId(), paymentId);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, paymentId, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, paymentId, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private PaymentDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID paymentId, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<PaymentDecision> replay = decisions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(actor, institution, replay.get(), hash);
            }
            Payment read = scoped.find(actor, institution, paymentId).orElseThrow(ResourceNotFoundException::new);
            read.requireDecidableBy(actor.accountId());
            PostingAccounts accounts = poster.accounts(institution);
            accounts.debitFor(PaymentMethod.BANK_TRANSFER);
            var ledgerActor = PaymentPoster.ledgerActor(actor, auditRequestId);
            poster.lockStudent(ledgerActor, read.studentId());
            Optional<PaymentDecision> underLock = decisions.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(actor, institution, underLock.get(), hash);
            }
            // Read again under the locks: a rejection or a confirmation that committed while this waited is seen now.
            Payment payment = payments.findForUpdate(institution, paymentId).orElseThrow(ResourceNotFoundException::new);
            payment.requireDecidableBy(actor.accountId());
            AllocationPlan plan = poster.plan(institution, payment.studentId(), payment.amount());
            UUID posting = poster.post(ledgerActor, accounts, PaymentMethod.BANK_TRANSFER, payment.id(),
                    payment.studentId(), payment.amount(), payment.receivedOn());
            PaymentDecision decision = payment.confirm(UUID.randomUUID(), actor.accountId(), posting, clock.instant(), key,
                    hash);
            decisions.insert(institution, decision);
            payments.advanceStatus(institution, payment.id(), PaymentStatus.CONFIRMED);
            int allocated = poster.save(institution, payment.id(), plan).size();
            auditLogWriter.append(PaymentsAudit.confirmed(actor, auditRequestId, payment, decision, allocated));
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
