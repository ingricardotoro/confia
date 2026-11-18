package com.confia.ledger.application;

import com.confia.ledger.domain.LedgerAccount;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the chart of accounts: {@code ledger.account.created} and {@code ledger.account.updated}, with the state
 * before and after of an edit. Built with {@link AuditJson}, never by concatenation; they hold the code, the name, the type, the
 * flags and the version of an account and no personal datum.
 */
final class AccountAudit {

    static final String ACCOUNT = "ledger.account";

    private AccountAudit() {
    }

    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry created(AuthenticatedActor actor, UUID requestId, LedgerAccount account) {
        return entry(actor, requestId, ACCOUNT + ".created", account.id(), null, json(account));
    }

    static AuditEntry updated(AuthenticatedActor actor, UUID requestId, LedgerAccount before, LedgerAccount after) {
        return entry(actor, requestId, ACCOUNT + ".updated", after.id(), json(before), json(after));
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, UUID entityId,
            String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), "staff", actor.accountId().toString(), null, null,
                requestId, null, action, ACCOUNT, entityId.toString(), "success", before, after, null, null);
    }

    static String json(LedgerAccount account) {
        return AuditJson.object().text("code", account.code()).text("name", account.name())
                .text("type", account.type().name()).bool("holdsStudentBalances", account.holdsStudentBalances())
                .bool("active", account.active()).number("version", account.version()).toJson();
    }
}
