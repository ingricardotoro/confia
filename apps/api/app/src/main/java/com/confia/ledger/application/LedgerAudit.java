package com.confia.ledger.application;

import com.confia.ledger.domain.LedgerTransaction;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;

/**
 * The entries of the ledger: {@code ledger.transaction.posted}, {@code ledger.transaction.reversed} and {@code
 * ledger.student_account.opened}. Identifiers, kinds, the currency, the total, the count of entries and the content hash:
 * no personal datum and no free text but the reason of a reversal, which the portal never sees.
 */
final class LedgerAudit {

    static final String TRANSACTION = "ledger.transaction";
    static final String STUDENT_ACCOUNT = "ledger.student_account";

    private LedgerAudit() {
    }

    static AuditEntry posted(LedgerActor actor, LedgerTransaction transaction, String contentHash) {
        String after = AuditJson.object().text("kind", transaction.kind().name())
                .text("sourceModule", transaction.sourceModule()).text("currency", transaction.currency().name())
                .text("total", transaction.total().toPlainString()).number("entries", transaction.entries().size())
                .text("contentHash", contentHash).toJson();
        return entry(actor, TRANSACTION + ".posted", TRANSACTION, transaction.id().toString(), after, null);
    }

    static AuditEntry reversed(LedgerActor actor, LedgerTransaction reversal, String contentHash) {
        String after = AuditJson.object().text("reverses", reversal.reversesTransactionId().toString())
                .text("currency", reversal.currency().name()).text("total", reversal.total().toPlainString())
                .number("entries", reversal.entries().size()).text("contentHash", contentHash).toJson();
        return entry(actor, TRANSACTION + ".reversed", TRANSACTION, reversal.id().toString(), after, reversal.reason());
    }

    static AuditEntry studentAccountOpened(LedgerActor actor, StudentAccountRef account) {
        String after = AuditJson.object().text("studentId", account.key().studentId().toString())
                .text("currency", account.key().currency().name()).toJson();
        return entry(actor, STUDENT_ACCOUNT + ".opened", STUDENT_ACCOUNT, account.id().toString(), after, null);
    }

    private static AuditEntry entry(LedgerActor actor, String action, String entityType, String entityId, String after,
            String reason) {
        return new AuditEntry(actor.institutionId(), actor.actorId(), actor.kind(), actor.actorId().toString(), null,
                null, actor.requestId(), null, action, entityType, entityId, "success", null, after, reason, null);
    }
}
