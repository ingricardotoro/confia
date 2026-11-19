package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.kernel.InstitutionId;
import java.util.List;
import java.util.Objects;

/**
 * Reads the whole of a session (its movements, its close, its approval) inside the transaction of the use case that asks, so
 * the answer of every route is built the same way and the expected cash is always the one the server computed.
 */
public final class SessionDetails {

    private final CashMovementRepository movements;
    private final CashCloseRepository closes;
    private final CashApprovalRepository approvals;

    public SessionDetails(CashMovementRepository movements, CashCloseRepository closes, CashApprovalRepository approvals) {
        this.movements = Objects.requireNonNull(movements, "movements");
        this.closes = Objects.requireNonNull(closes, "closes");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
    }

    SessionDetail load(InstitutionId institution, CashSession session) {
        List<CashMovement> rows = movements.findBySession(institution, session.id());
        CashClose close = closes.findBySession(institution, session.id()).orElse(null);
        CashApproval approval = approvals.findBySession(institution, session.id()).orElse(null);
        return SessionDetail.of(session, rows, close, approval);
    }
}
