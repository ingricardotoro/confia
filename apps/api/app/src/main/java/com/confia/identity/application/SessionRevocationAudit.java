package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import java.util.UUID;

/**
 * The entry that says sessions were ended on purpose (session-endpoints-cookie-and-csrf design.md
 * decisions 15 and 17), shared by {@link EndCurrentSession} and {@link EndAllSessions} so both write
 * the same words. Entity: the account, as the entry of the password reset. The document is built
 * with {@link AuditJson}, never by concatenation, and carries the reason and the number of families,
 * and no token, no hash and no fingerprint.
 */
final class SessionRevocationAudit {

    static final String ACTION_REVOKED = "identity.session.revoked";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";
    private static final String OUTCOME_SUCCESS = "success";

    private SessionRevocationAudit() {
    }

    static AuditEntry logout(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, int families) {
        return entry(SessionRevocationReason.LOGOUT, institutionId, accountId, requestId, families);
    }

    /** The entry of the first verification of a second factor, which ends the other sessions of the account. */
    static AuditEntry mfaChange(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, int families) {
        return entry(SessionRevocationReason.MFA_CHANGE, institutionId, accountId, requestId, families);
    }

    private static AuditEntry entry(SessionRevocationReason reason, InstitutionId institutionId,
            StaffAccountId accountId, UUID requestId, int families) {
        String account = accountId.value().toString();
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF, account,
                null, null, requestId, null, ACTION_REVOKED, STAFF_ACCOUNT_ENTITY_TYPE, account,
                OUTCOME_SUCCESS, null, afterValue(reason, families), null, null);
    }

    /** The {@code after_value} of the entry: why, and how many families. */
    static String afterValue(SessionRevocationReason reason, int families) {
        return AuditJson.object().text("reason", reason.code()).number("families", families)
                .toJson();
    }
}
