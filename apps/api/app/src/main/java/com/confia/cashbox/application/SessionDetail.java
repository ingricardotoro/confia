package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.kernel.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A session with its movements, its close and its approval, and the expected cash computed by the server. {@code expected} is
 * what the close recorded once closed, and the float plus the movements while open. {@code close} and {@code approval} are null
 * until they exist. It holds no domain type.
 *
 * @param status {@code open}, {@code pending_approval} or {@code closed}
 */
public record SessionDetail(UUID id, UUID cashierId, String status, Money openingFloat, Money expected,
        List<MovementView> movements, CloseView close, ApprovalView approval, Instant openedAt) {

    public SessionDetail {
        movements = List.copyOf(movements);
    }

    static SessionDetail of(CashSession session, List<CashMovement> movements, CashClose close, CashApproval approval) {
        Money expected = close != null ? close.expectedAmount() : session.expected(movements);
        return new SessionDetail(session.id(), session.cashierId(), session.status().code(), session.openingFloat(),
                expected, movements.stream().map(MovementView::of).toList(), close == null ? null : CloseView.of(close),
                approval == null ? null : ApprovalView.of(approval), session.openedAt());
    }
}
