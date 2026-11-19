package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashAmounts;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.ResourceNotFoundException;
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
 * The close of a session with the physical count the cashier declares (INV-23). The server computes the expected cash from the
 * float and the movements, and the difference is declared minus expected: with no difference the session is {@code closed};
 * with one it needs a reason of 20 to 500 characters and stays {@code pending_approval} until someone other than the cashier
 * approves. The session is locked {@code FOR UPDATE} first, so a movement that races the close is either in the count or refused
 * (the database also refuses a movement on a session that left {@code open}). Only the cashier of the session closes it.
 */
public final class CloseSession {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashCloseRepository closes;
    private final SessionDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public CloseSession(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashMovementRepository movements, CashCloseRepository closes, SessionDetails details,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.movements = Objects.requireNonNull(movements, "movements");
        this.closes = Objects.requireNonNull(closes, "closes");
        this.details = Objects.requireNonNull(details, "details");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param declaredAmount the counted cash as a decimal string, zero or more with at most two decimals
     * @param differenceReason required when the count differs from the expected amount; ignored otherwise
     */
    public SessionDetail close(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID sessionId,
            String declaredAmount, String differenceReason) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sessionId, "sessionId");
        UUID auditRequestId = CashboxRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = CashboxRequests.key(idempotencyKey);
        Money declared = CashAmounts.parseNotNegative(declaredAmount);
        String hash = CashboxRequests.hash("close", actor.accountId(), sessionId, declared.toPlainString(),
                differenceReason == null ? "" : differenceReason.strip());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, sessionId, declared, differenceReason, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, sessionId, declared, differenceReason, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private SessionDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID sessionId, Money declared, String reason, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<CashClose> replay = closes.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            CashSession session = sessions.findForUpdate(institution, sessionId)
                    .filter(found -> found.cashierId().equals(actor.accountId()))
                    .orElseThrow(ResourceNotFoundException::new);
            Optional<CashClose> underLock = closes.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            List<CashMovement> existing = movements.findBySession(institution, sessionId);
            CashClose close = session.close(UUID.randomUUID(), declared, reason, existing, actor.accountId(),
                    clock.instant(), key, hash);
            closes.insert(institution, close);
            sessions.advanceStatus(institution, sessionId, close.resultingStatus());
            auditLogWriter.append(CashboxAudit.sessionClosed(actor, auditRequestId, close));
            return details.load(institution, session.afterClose(close));
        });
    }

    private SessionDetail answerReplay(InstitutionId institution, CashClose existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        CashSession session = sessions.findById(institution, existing.sessionId())
                .orElseThrow(ResourceNotFoundException::new);
        return details.load(institution, session);
    }
}
