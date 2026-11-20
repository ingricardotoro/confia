package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionStatus;
import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The approval of a close with a difference (INV-23; segregation of duties, docs/03-seguridad.md section 5.3). The approver must
 * hold {@code cashbox:approve} (decided before the controller runs) and must not be the cashier of the session: that is {@code
 * 403 cash-approval-same-actor}, and the database refuses it too. Only a session pending approval is approved; one without a
 * difference or already approved answers {@code 409 cash-session-not-awaiting-approval}. The session is locked {@code FOR UPDATE}
 * first, so two approvals serialize and the second finds the first.
 */
public final class ApproveClose {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final CashApprovalRepository approvals;
    private final SessionDetails details;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ApproveClose(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashApprovalRepository approvals, SessionDetails details, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.details = Objects.requireNonNull(details, "details");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param note what the approver found, from 1 to 500 characters */
    public SessionDetail approve(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID sessionId,
            String note) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sessionId, "sessionId");
        UUID auditRequestId = CashboxRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = CashboxRequests.key(idempotencyKey);
        String hash = CashboxRequests.hash("approve", actor.accountId(), sessionId, note == null ? "" : note.strip());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, sessionId, note, hash);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, sessionId, note, hash);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private SessionDetail attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID sessionId, String note, String hash) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<CashApproval> replay = approvals.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            CashSession session = sessions.findForUpdate(institution, sessionId)
                    .orElseThrow(ResourceNotFoundException::new);
            Optional<CashApproval> underLock = approvals.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            CashApproval approval = session.approve(UUID.randomUUID(), actor.accountId(), note, clock.instant(), key,
                    hash);
            approvals.insert(institution, approval);
            sessions.advanceStatus(institution, sessionId, CashSessionStatus.CLOSED);
            auditLogWriter.append(CashboxAudit.sessionApproved(actor, auditRequestId, approval));
            return details.load(institution, session.afterApproval());
        });
    }

    private SessionDetail answerReplay(InstitutionId institution, CashApproval existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        CashSession session = sessions.findById(institution, existing.sessionId())
                .orElseThrow(ResourceNotFoundException::new);
        return details.load(institution, session);
    }
}
