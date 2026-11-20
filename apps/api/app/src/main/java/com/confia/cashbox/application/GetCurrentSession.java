package com.confia.cashbox.application;

import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.util.Objects;

/** The open session of the cashier that asks, with its expected cash and its movements, or 404 when it has none. */
public final class GetCurrentSession {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final SessionDetails details;

    public GetCurrentSession(TransactionRunner transactionRunner, CashSessionRepository sessions,
            SessionDetails details) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.details = Objects.requireNonNull(details, "details");
    }

    public SessionDetail current(AuthenticatedActor actor, String requestId) {
        Objects.requireNonNull(actor, "actor");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(CashboxRequests.requestId(requestId).toString()),
                () -> sessions.findOpenByCashier(institution, actor.accountId())
                        .map(session -> details.load(institution, session))
                        .orElseThrow(ResourceNotFoundException::new));
    }
}
