package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.InvalidCashboxValueException;
import com.confia.cashbox.domain.MovementType;
import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.cashbox.domain.CashAmounts;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A deposit, a withdrawal or an adjustment in the open session of the cashier (INV-21). The session is locked {@code FOR UPDATE}
 * first, so two movements and a close serialize, the expected cash never ends below zero and a closed session accepts nothing;
 * the database refuses the same movement by trigger. Only the cashier of the session moves its cash: another cashier's session
 * and an unknown one look the same (404). The Idempotency-Key is stored permanently on the movement with the hash of the request.
 */
public final class RecordMovement {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public RecordMovement(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashMovementRepository movements, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.movements = Objects.requireNonNull(movements, "movements");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param type {@code deposit}, {@code withdrawal} or {@code adjustment}
     * @param amount a decimal string: positive for a deposit and a withdrawal, signed and not zero for an adjustment
     * @param notes required for an adjustment (20 to 500 characters), optional for the others
     */
    public MovementResult record(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID sessionId,
            String type, String amount, String notes) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sessionId, "sessionId");
        UUID auditRequestId = CashboxRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = CashboxRequests.key(idempotencyKey);
        MovementType movementType = MovementType.ofCode(type).orElseThrow(InvalidCashboxValueException::new);
        Money money = CashAmounts.parse(amount);
        String hash = CashboxRequests.hash("movement", actor.accountId(), sessionId, movementType.code(),
                money.toPlainString(), notes == null ? "" : notes.strip());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, sessionId, movementType, money, notes, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, sessionId, movementType, money, notes, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private MovementResult attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID sessionId, MovementType type, Money amount, String notes, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<CashMovement> replay = movements.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            CashSession session = sessions.findForUpdate(institution, sessionId)
                    .filter(found -> found.cashierId().equals(actor.accountId()))
                    .orElseThrow(ResourceNotFoundException::new);
            Optional<CashMovement> underLock = movements.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            List<CashMovement> existing = movements.findBySession(institution, sessionId);
            CashMovement movement = session.recordMovement(UUID.randomUUID(), type, amount, notes, existing,
                    actor.accountId(), clock.instant(), key, hash);
            movements.insert(institution, movement);
            auditLogWriter.append(CashboxAudit.movementRecorded(actor, auditRequestId, movement));
            return MovementResult.of(movement, session.expected(existing).add(movement.effect()));
        });
    }

    private MovementResult answerReplay(InstitutionId institution, CashMovement existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        // The figure of the original call, stored with the movement, not the expected of today.
        return MovementResult.of(existing, movements.expectedAfter(institution, existing.id()));
    }
}
