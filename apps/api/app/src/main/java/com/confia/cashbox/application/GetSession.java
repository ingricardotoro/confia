package com.confia.cashbox.application;

import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.util.Objects;
import java.util.UUID;

/**
 * One session with its movements, its close and its approval: the base of the printable report of the close. A session of
 * another institution, an unknown one and (for an actor that reads only its own) another cashier's session look the same: 404.
 */
public final class GetSession {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final SessionDetails details;
    private final SessionScope scope;

    public GetSession(TransactionRunner transactionRunner, CashSessionRepository sessions, SessionDetails details,
            SessionScope scope) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.details = Objects.requireNonNull(details, "details");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public SessionDetail get(AuthenticatedActor actor, String requestId, UUID sessionId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(sessionId, "sessionId");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(CashboxRequests.requestId(requestId).toString()),
                () -> {
                    boolean everySession = scope.readsEverySession(actor);
                    return sessions.findById(institution, sessionId)
                            .filter(session -> everySession || session.cashierId().equals(actor.accountId()))
                            .map(session -> details.load(institution, session))
                            .orElseThrow(ResourceNotFoundException::new);
                });
    }
}
