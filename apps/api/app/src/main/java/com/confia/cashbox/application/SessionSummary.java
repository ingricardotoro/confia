package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashSession;
import com.confia.kernel.Money;
import java.time.Instant;
import java.util.UUID;

/** One row of the list of sessions; the amounts of the close and the two dates are null until they exist. */
public record SessionSummary(UUID id, UUID cashierId, String status, Money openingFloat, Money declaredAmount,
        Money expectedAmount, Money differenceAmount, Instant openedAt, Instant closedAt, Instant approvedAt) {

    static SessionSummary of(CashSession session, CashClose close, CashApproval approval) {
        return new SessionSummary(session.id(), session.cashierId(), session.status().code(), session.openingFloat(),
                close == null ? null : close.declaredAmount(), close == null ? null : close.expectedAmount(),
                close == null ? null : close.differenceAmount(), session.openedAt(),
                close == null ? null : close.closedAt(), approval == null ? null : approval.approvedAt());
    }
}
