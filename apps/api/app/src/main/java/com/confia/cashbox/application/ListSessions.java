package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionStatus;
import com.confia.cashbox.domain.InvalidCashboxValueException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A page of cash sessions, filtered by status, cashier and day opened. An actor that reads only its own sessions is always
 * restricted to them, whatever cashier it asked for. The page size is limited by the server.
 */
public final class ListSessions {

    private final TransactionRunner transactionRunner;
    private final CashSessionRepository sessions;
    private final CashCloseRepository closes;
    private final CashApprovalRepository approvals;
    private final SessionScope scope;

    public ListSessions(TransactionRunner transactionRunner, CashSessionRepository sessions, CashCloseRepository closes,
            CashApprovalRepository approvals, SessionScope scope) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.closes = Objects.requireNonNull(closes, "closes");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public Page<SessionSummary> list(AuthenticatedActor actor, String requestId, SessionFilter filter,
            PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw new InvalidCashboxValueException();
        }
        if (filter.status() != null && CashSessionStatus.ofCode(filter.status()).isEmpty()) {
            throw new InvalidCashboxValueException();
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(CashboxRequests.requestId(requestId).toString()),
                () -> {
                    SessionFilter effective = scope.readsEverySession(actor) ? filter
                            : filter.onlyCashier(actor.accountId());
                    Page<CashSession> found = sessions.list(institution, effective, page);
                    List<UUID> ids = found.items().stream().map(CashSession::id).toList();
                    Map<UUID, CashClose> closed = closes.findBySessions(institution, ids);
                    Map<UUID, CashApproval> approved = approvals.findBySessions(institution, ids);
                    return found.map(session -> SessionSummary.of(session, closed.get(session.id()),
                            approved.get(session.id())));
                });
    }
}
