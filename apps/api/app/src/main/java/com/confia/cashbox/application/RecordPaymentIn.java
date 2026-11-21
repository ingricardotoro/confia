package com.confia.cashbox.application;

import com.confia.cashbox.application.api.CashPayments;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.MovementType;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code payment_in} movement of a cash payment (docs/cortes/payments-register.md; INV-20, INV-21), written INSIDE the
 * transaction of {@code payments}: the open session of the cashier is found and locked {@code FOR UPDATE} (so the movement and a
 * concurrent close take turns), the movement is inserted and audited, and nothing is committed here. The cashier is the actor of
 * the transaction. The movement key is derived from the payment, so the movement is permanent and unique per payment.
 */
public final class RecordPaymentIn implements CashPayments {

    private static final String KEY_PREFIX = "payment-in:";

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RecordPaymentIn(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashMovementRepository movements, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.movements = Objects.requireNonNull(movements, "movements");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<PaymentIn> recordPaymentIn(AuthenticatedActor actor, UUID requestId, UUID paymentId, Money amount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        if (!transactionRunner.isTransactionActive()) {
            throw new IllegalStateException("a payment_in movement is written inside the transaction of the payment");
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        Optional<CashSession> open = sessions.findOpenByCashier(institution, actor.accountId());
        if (open.isEmpty()) {
            return Optional.empty();
        }
        // Locked, and read again under the lock: a close that committed while this waited is seen now.
        Optional<CashSession> locked = sessions.findForUpdate(institution, open.get().id()).filter(CashSession::isOpen);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        CashSession session = locked.get();
        List<CashMovement> existing = movements.findBySession(institution, session.id());
        String hash = CashboxRequests.hash("payment-in", actor.accountId(), paymentId, amount.toPlainString());
        CashMovement movement = session.recordMovement(UUID.randomUUID(), MovementType.PAYMENT_IN, amount, null, existing,
                actor.accountId(), clock.instant(), KEY_PREFIX + paymentId, hash);
        movements.insert(institution, movement);
        auditLogWriter.append(CashboxAudit.movementRecorded(actor, requestId, movement));
        return Optional.of(new PaymentIn(session.id(), movement.id()));
    }
}
