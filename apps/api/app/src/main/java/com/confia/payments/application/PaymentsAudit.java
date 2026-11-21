package com.confia.payments.application;

import com.confia.payments.application.PostingAccountRepository.PostingAccountRow;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentDecision;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the payments: {@code payments.payment.registered|confirmed|rejected} and {@code
 * payments.posting_account.updated}. They are built with {@link AuditJson} and carry identifiers, the method, the amount, the
 * status and dates. They never carry a name, a note, the reason of a rejection (only its length) or the whole bank reference
 * (only its last four characters, masked): the free text may name a person and the reference identifies a bank operation.
 */
final class PaymentsAudit {

    static final String PAYMENT = "payments.payment";
    static final String POSTING_ACCOUNT = "payments.posting_account";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private PaymentsAudit() {
    }

    /** The registration: cash is born confirmed and posted, a transfer is born declared. */
    static AuditEntry registered(AuthenticatedActor actor, UUID requestId, Payment payment) {
        AuditJson.Builder json = AuditJson.object().text("studentId", payment.studentId().toString())
                .text("method", payment.method().name()).text("status", payment.status().code())
                .text("amount", payment.amount().toPlainString()).text("currency", payment.amount().currency().name())
                .text("receivedOn", payment.receivedOn().toString());
        if (payment.transfer() != null) {
            json.text("reference", payment.transfer().masked());
        }
        if (payment.cashSessionId() != null) {
            json.text("cashSessionId", payment.cashSessionId().toString());
        }
        if (payment.ledgerTransactionId() != null) {
            json.text("ledgerTransactionId", payment.ledgerTransactionId().toString());
        }
        if (payment.notes() != null) {
            json.number("notesLength", payment.notes().length());
        }
        return entry(actor, requestId, PAYMENT + ".registered", payment.id(), null, json.toJson(), null);
    }

    /** The confirmation of a transfer: the status it left, the posting, how many charges received something. */
    static AuditEntry confirmed(AuthenticatedActor actor, UUID requestId, Payment payment, PaymentDecision decision,
            int allocations) {
        return entry(actor, requestId, PAYMENT + ".confirmed", payment.id(),
                AuditJson.object().text("status", "declared").toJson(),
                AuditJson.object().text("status", "confirmed").text("decisionId", decision.id().toString())
                        .text("ledgerTransactionId", decision.ledgerTransactionId().toString())
                        .text("amount", payment.amount().toPlainString()).number("allocations", allocations)
                        .text("reference", payment.transfer().masked()).toJson(),
                decision.decidedBy());
    }

    /** The rejection of a transfer. The reason may name a person, so only its length is recorded. */
    static AuditEntry rejected(AuthenticatedActor actor, UUID requestId, Payment payment, PaymentDecision decision) {
        return entry(actor, requestId, PAYMENT + ".rejected", payment.id(),
                AuditJson.object().text("status", "declared").toJson(),
                AuditJson.object().text("status", "rejected").text("decisionId", decision.id().toString())
                        .number("reasonLength", decision.reason().length())
                        .text("reference", payment.transfer().masked()).toJson(),
                decision.decidedBy());
    }

    /** The setting of one entry of the posting map: {@code before} is null when the entry was first set. */
    static AuditEntry postingAccountSet(AuthenticatedActor actor, UUID requestId, PostingAccountRow before,
            PostingAccountRow after) {
        return entry(actor, requestId, POSTING_ACCOUNT + ".updated", after.id(), before == null ? null : json(before),
                json(after), null, POSTING_ACCOUNT);
    }

    private static String json(PostingAccountRow row) {
        return AuditJson.object().text("purpose", row.purpose().name())
                .text("ledgerAccountId", row.ledgerAccountId().toString()).number("version", row.version()).toJson();
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId, String before,
            String after, UUID approverId) {
        return entry(actor, requestId, action, entityId, before, after, approverId, PAYMENT);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId, String before,
            String after, UUID approverId, String entityType) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType, entityId.toString(),
                OUTCOME_SUCCESS, before, after, null, approverId);
    }
}
