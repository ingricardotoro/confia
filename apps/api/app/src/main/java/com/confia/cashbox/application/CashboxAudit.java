package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the cash sessions: {@code cashbox.session.opened|closed|approved} and {@code cashbox.movement.recorded}. They
 * are built with {@link AuditJson} and carry identifiers, the type, the amounts and the status; the free text (a note, the reason
 * of a difference) may name a person, so only its length is recorded and the text lives in its own record.
 */
final class CashboxAudit {

    static final String SESSION = "cashbox.session";
    static final String MOVEMENT = "cashbox.movement";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private CashboxAudit() {
    }

    static AuditEntry sessionOpened(AuthenticatedActor actor, UUID requestId, CashSession session) {
        return entry(actor, requestId, SESSION + ".opened", SESSION, session.id(), null,
                AuditJson.object().text("status", session.status().code())
                        .text("openingFloat", session.openingFloat().toPlainString())
                        .text("currency", session.openingFloat().currency().name()).toJson(), null);
    }

    static AuditEntry movementRecorded(AuthenticatedActor actor, UUID requestId, CashMovement movement) {
        AuditJson.Builder json = AuditJson.object().text("sessionId", movement.sessionId().toString())
                .text("type", movement.type().code()).text("amount", movement.amount().toPlainString())
                .text("currency", movement.amount().currency().name());
        if (movement.notes() != null) {
            json.number("notesLength", movement.notes().length());
        }
        return entry(actor, requestId, MOVEMENT + ".recorded", MOVEMENT, movement.id(), null, json.toJson(), null);
    }

    /** The close: the declared and expected amounts, the difference and the status the session moved to. */
    static AuditEntry sessionClosed(AuthenticatedActor actor, UUID requestId, CashClose close) {
        AuditJson.Builder json = AuditJson.object().text("closeId", close.id().toString())
                .text("declaredAmount", close.declaredAmount().toPlainString())
                .text("expectedAmount", close.expectedAmount().toPlainString())
                .text("differenceAmount", close.differenceAmount().toPlainString())
                .text("status", close.resultingStatus().code());
        if (close.differenceReason() != null) {
            json.number("differenceReasonLength", close.differenceReason().length());
        }
        return entry(actor, requestId, SESSION + ".closed", SESSION, close.sessionId(),
                AuditJson.object().text("status", "open").toJson(), json.toJson(), null);
    }

    /** The approval of a close with a difference; the approver is also recorded as such. */
    static AuditEntry sessionApproved(AuthenticatedActor actor, UUID requestId, CashApproval approval) {
        return entry(actor, requestId, SESSION + ".approved", SESSION, approval.sessionId(),
                AuditJson.object().text("status", "pending_approval").toJson(),
                AuditJson.object().text("approvalId", approval.id().toString()).text("status", "closed")
                        .number("noteLength", approval.note().length()).toJson(),
                approval.approvedBy());
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType,
            UUID entityId, String before, String after, UUID approverId) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType, entityId.toString(),
                OUTCOME_SUCCESS, before, after, null, approverId);
    }
}
