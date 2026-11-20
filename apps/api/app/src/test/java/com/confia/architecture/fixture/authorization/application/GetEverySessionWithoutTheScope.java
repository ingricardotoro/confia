package com.confia.architecture.fixture.authorization.application;

import com.confia.cashbox.application.CashSessionRepository;
import com.confia.cashbox.domain.CashSession;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * A permanent, deliberately invalid fixture: a read use case of the cash sessions that never asks the {@code SessionScope}
 * how far the caller may read, so a cashier would read the sessions of everyone.
 */
public final class GetEverySessionWithoutTheScope {

    private final CashSessionRepository sessions;

    public GetEverySessionWithoutTheScope(CashSessionRepository sessions) {
        this.sessions = sessions;
    }

    public Optional<CashSession> get(InstitutionId institution, UUID id) {
        return sessions.findById(institution, id);
    }
}
