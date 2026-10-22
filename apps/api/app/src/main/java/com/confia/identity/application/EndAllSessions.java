package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ends every live session of the account that holds an access token (session-endpoints-cookie-and-csrf
 * design.md decision 15; scenario I200): the use case behind {@code DELETE /api/v1/auth/sessions}.
 * It has no controller yet. The institution and the account are the actor's, never a value the
 * request carries, and the transaction runs with the actor's own security context, so row-level
 * security keeps another institution out whatever the arguments say.
 *
 * <p>In <b>one</b> transaction, and in the global order of locks, <b>account, then families</b>: the
 * row of the account is locked first, which serializes this against a password reset, an enrollment
 * and an issuance for the same account, and then {@link AccountSessionRevoker#revokeAllLive} locks
 * the live families {@code ORDER BY id}. A rotation takes only the lock of its family, so it can
 * wait for this closing and never form a cycle with it; once it gets the lock it reads the family
 * revoked. The current family is among the ones revoked, and a family that was already revoked keeps
 * its reason and its instant. One entry is written with the number of families revoked, also when it
 * is zero, as the entry of the password reset does.
 */
public final class EndAllSessions {

    private final TransactionRunner transactionRunner;
    private final StaffAccountRepository accounts;
    private final AccountSessionRevoker sessionRevoker;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public EndAllSessions(TransactionRunner transactionRunner, StaffAccountRepository accounts,
            AccountSessionRevoker sessionRevoker, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessionRevoker = Objects.requireNonNull(sessionRevoker, "sessionRevoker");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param actor who holds the access token that asked for it
     * @param requestId the identifier of the request, for the entry; a blank one gets a fresh
     *     identifier
     * @return how many families this call revoked
     */
    public int execute(AuthenticatedActor actor, String requestId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestId, "requestId");
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        InstitutionId institutionId = new InstitutionId(actor.institutionId());
        StaffAccountId accountId = new StaffAccountId(actor.accountId());

        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            // 1. The account first: account, then families.
            accounts.lockById(institutionId, accountId).orElseThrow(() -> new IllegalStateException(
                    "sessions cannot be ended for an account that does not exist"));

            // 2. The instant belongs to the moment the closing really runs, after the lock.
            Instant now = clock.instant();
            int revoked = sessionRevoker.revokeAllLive(institutionId, accountId,
                    SessionRevocationReason.LOGOUT, now);
            auditLogWriter.append(SessionRevocationAudit.logout(institutionId, accountId,
                    auditRequestId, revoked));
            return revoked;
        });
    }
}
