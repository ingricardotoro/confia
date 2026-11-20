package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashAmounts;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionAlreadyOpenException;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The opening of the session of a cashier with a float (INV-22: one open session per cashier). The Idempotency-Key is stored
 * permanently on the session with the hash of what it asked: the same key from the same cashier with the same float answers the
 * same session, the same key with another request answers {@code 422 idempotency-payload-mismatch}, and another key while a
 * session is open answers {@code 409 cash-session-already-open}. The unique partial index of the database decides two
 * simultaneous openings; the loser starts again and finds the winner.
 */
public final class OpenSession {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final SessionDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public OpenSession(TransactionRunner transactionRunner, CashSessionRepository sessions,
            SessionDetails details, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.details = Objects.requireNonNull(details, "details");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param openingFloat the float as a decimal string, zero or more with at most two decimals
     */
    public SessionDetail open(AuthenticatedActor actor, String requestId, String idempotencyKey, String openingFloat) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = CashboxRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = CashboxRequests.key(idempotencyKey);
        Money floatAmount = CashAmounts.parseNotNegative(openingFloat);
        String hash = CashboxRequests.hash("open", actor.accountId(), floatAmount.toPlainString());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, floatAmount, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, floatAmount, hash);
            } catch (RequestKeyTakenException again) {
                throw new CashSessionAlreadyOpenException();
            }
        }
    }

    private SessionDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            Money floatAmount, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<CashSession> replay = sessions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                if (!replay.get().answers(hash)) {
                    throw new IdempotencyPayloadMismatchException();
                }
                return details.load(institution, replay.get());
            }
            if (sessions.findOpenByCashier(institution, actor.accountId()).isPresent()) {
                throw new CashSessionAlreadyOpenException();
            }
            CashSession session = CashSession.open(UUID.randomUUID(), actor.accountId(), floatAmount, clock.instant(), key,
                    hash);
            sessions.insert(institution, session);
            auditLogWriter.append(CashboxAudit.sessionOpened(actor, auditRequestId, session));
            return details.load(institution, session);
        });
    }
}
