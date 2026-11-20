package com.confia.architecture.fixture.authorization.web;

import com.confia.cashbox.application.CashSessionRepository;
import com.confia.cashbox.domain.CashSession;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * A permanent, deliberately invalid fixture: a class of a {@code web} package that reads the cash sessions straight from the
 * repository, with no use case and so with no {@code SessionScope}. A cashier would read the session of another.
 */
public final class BadWebReadsTheCashSessionRepository {

    private final CashSessionRepository sessions;

    public BadWebReadsTheCashSessionRepository(CashSessionRepository sessions) {
        this.sessions = sessions;
    }

    public Optional<CashSession> anySession(InstitutionId institution, UUID id) {
        return sessions.findById(institution, id);
    }
}
