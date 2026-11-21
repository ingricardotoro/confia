package com.confia.payments.application;

import com.confia.cashbox.application.api.CashPayments;
import com.confia.charges.application.api.PayableCharges;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.AllocationPlan;
import com.confia.payments.domain.CashSessionNotOpenException;
import com.confia.payments.domain.DuplicateTransferReferenceException;
import com.confia.payments.domain.InvalidPaymentsValueException;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentMethod;
import com.confia.payments.domain.PaymentRequest;
import com.confia.payments.domain.PostingAccounts;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The registration of a payment (docs/cortes/payments-register.md; INV-17 to INV-20, INV-24), in ONE database transaction opened by
 * the transactional component, so either everything happens or nothing does.
 *
 * <p><b>Cash</b> is born confirmed. Order, which is the lock order of the whole financial flow (the student sub-account of the
 * ledger first, ADR-0010): (1) the request key is looked up; (2) the posting accounts are proven; (3) the sub-account of the
 * student is locked {@code FOR UPDATE}; (4) under the lock the key is read again, so two simultaneous requests serialize and the
 * second finds the first; (5) the {@code payment_in} movement is left in the open session of the cashier (refused when there is
 * none, INV-20); (6) the open charges and what is already allocated to them are read under the lock and the plan is computed;
 * (7) the ledger posts the payment; (8) the payment and its allocations are inserted; (9) the audit entry is written. The
 * database repeats the rules by trigger.
 *
 * <p><b>A transfer</b> is declared: it is stored and audited, and touches no cash, no ledger and no allocation. The bank and the
 * reference of one that was not rejected are not repeated.
 *
 * <p>The body can run twice (retry on deadlock) and produces no effect outside the transaction. The {@code Idempotency-Key} is
 * stored permanently on the payment with the hash of what it asked, which includes the actor: the same key and the same request
 * answer the same payment, the same key and another request is refused. The institution and the cashier are the ones of the
 * actor, never a member of a request.
 */
public final class RegisterPayment {

    /** What the cashier asked to register, as the raw values of the request. */
    public record Command(UUID studentId, UUID guardianId, String method, String amount, LocalDate receivedOn,
            String reference, String bankName, String notes) {

        @Override
        public String toString() {
            return "Command[studentId=" + studentId + ", method=" + method + "]";
        }
    }

    private final TransactionRunner transactionRunner;
    private final PaymentRepository payments;
    private final StudentDirectory students;
    private final CashPayments cash;
    private final PaymentPoster poster;
    private final PaymentDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RegisterPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, PostingAccountRepository map,
            StudentDirectory students, CashPayments cash, LedgerPostingApi ledger, LedgerAccountDirectory ledgerAccounts,
            PayableCharges charges, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.students = Objects.requireNonNull(students, "students");
        this.cash = Objects.requireNonNull(cash, "cash");
        this.poster = new PaymentPoster(ledger, ledgerAccounts, charges, map, allocations, clock);
        this.details = new PaymentDetails(decisions, allocations, charges, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers the payment.
     *
     * @throws com.confia.payments.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws com.confia.payments.domain.InvalidPaymentsValueException when a value breaks a rule (INV-19)
     * @throws CashSessionNotOpenException when cash is collected without an open session of the cashier (INV-20)
     * @throws com.confia.payments.domain.PostingAccountsIncompleteException when cash needs a posting account that is not set
     * @throws DuplicateTransferReferenceException when the bank and the reference of a transfer are in use
     * @throws ResourceNotFoundException when the student does not exist in the institution of the actor
     */
    public PaymentDetail register(AuthenticatedActor actor, String requestId, String idempotencyKey, Command command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        UUID auditRequestId = PaymentRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = PaymentRequests.key(idempotencyKey);
        PaymentRequest request = validated(command);
        String hash = PaymentRequests.hash("register", actor.accountId(), request.canonical());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, request, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            // Another request committed the same key first: the second pass finds it by key and answers a replay, or refuses
            // it as another request. A second loss is the same answer, never an internal error.
            try {
                return attempt(actor, auditRequestId, institution, key, request, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    /** The domain validates the raw values; a rule about one field is named for the edge as {@link RejectedPaymentFieldException}. */
    private PaymentRequest validated(Command command) {
        try {
            return PaymentRequest.of(command.studentId(), command.guardianId(), command.method(), command.amount(),
                    command.receivedOn(), PaymentRequests.today(clock), command.reference(), command.bankName(),
                    command.notes());
        } catch (InvalidPaymentsValueException e) {
            if (e.field() == null) {
                throw e;
            }
            throw new RejectedPaymentFieldException(e.field(), e.reason());
        }
    }

    private PaymentDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            PaymentRequest request, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<Payment> replay = payments.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            students.find(institution, request.studentId()).orElseThrow(ResourceNotFoundException::new);
            return request.method() == PaymentMethod.CASH
                    ? registerCash(actor, auditRequestId, institution, key, request, hash)
                    : declareTransfer(actor, auditRequestId, institution, key, request, hash);
        });
    }

    private PaymentDetail registerCash(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution,
            String key, PaymentRequest request, String hash) {
        PostingAccounts accounts = poster.accounts(institution);
        accounts.debitFor(PaymentMethod.CASH);
        var ledgerActor = PaymentPoster.ledgerActor(actor, auditRequestId);
        // The lock comes before the second reading and before the charges are read: what another payment or void committed while
        // this one waited is seen now.
        poster.lockStudent(ledgerActor, request.studentId());
        Optional<Payment> underLock = payments.findByRequestKey(institution, key);
        if (underLock.isPresent()) {
            return answerReplay(institution, underLock.get(), hash);
        }
        UUID id = UUID.randomUUID();
        CashPayments.PaymentIn in = cash.recordPaymentIn(actor, auditRequestId, id, request.amount())
                .orElseThrow(CashSessionNotOpenException::new);
        AllocationPlan plan = poster.plan(institution, request.studentId(), request.amount());
        UUID posting = poster.post(ledgerActor, accounts, PaymentMethod.CASH, id, request.studentId(), request.amount(),
                request.receivedOn());
        Payment payment = Payment.cash(id, request, actor.accountId(), clock.instant(), key, hash, in.sessionId(),
                in.movementId(), posting);
        payments.insert(institution, payment);
        poster.save(institution, id, plan);
        auditLogWriter.append(PaymentsAudit.registered(actor, auditRequestId, payment));
        return details.of(institution, payment);
    }

    private PaymentDetail declareTransfer(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution,
            String key, PaymentRequest request, String hash) {
        if (payments.transferReferenceInUse(institution, request.transfer().bankName(), request.transfer().reference())) {
            throw new DuplicateTransferReferenceException();
        }
        Payment payment = Payment.declaredTransfer(UUID.randomUUID(), request, actor.accountId(), clock.instant(), key, hash);
        payments.insert(institution, payment);
        auditLogWriter.append(PaymentsAudit.registered(actor, auditRequestId, payment));
        return details.of(institution, payment);
    }

    private PaymentDetail answerReplay(InstitutionId institution, Payment existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        return details.of(institution, existing);
    }
}
