package com.confia.cashbox.web;

import com.confia.cashbox.application.ApprovalView;
import com.confia.cashbox.application.CloseView;
import com.confia.cashbox.application.MovementResult;
import com.confia.cashbox.application.MovementView;
import com.confia.cashbox.application.Page;
import com.confia.cashbox.application.SessionDetail;
import com.confia.cashbox.application.SessionSummary;

/** The output DTO of every route of the cash sessions. Every amount is rounded by the server to the minor unit. */
final class CashboxMapper {

    private CashboxMapper() {
    }

    static SessionResponse toResponse(SessionDetail detail) {
        return new SessionResponse(detail.id(), detail.cashierId(), detail.status(), CashMoney.of(detail.openingFloat()),
                CashMoney.of(detail.expected()), detail.movements().stream().map(CashboxMapper::toResponse).toList(),
                toResponse(detail.close()), toResponse(detail.approval()), detail.openedAt());
    }

    static RecordedMovementResponse toResponse(MovementResult result) {
        return new RecordedMovementResponse(toResponse(result.movement()), CashMoney.of(result.expectedAfter()));
    }

    static SessionPageResponse toResponse(Page<SessionSummary> page) {
        return new SessionPageResponse(page.items().stream().map(CashboxMapper::toSummary).toList(), page.total(),
                page.page(), page.size());
    }

    private static MovementResponse toResponse(MovementView movement) {
        return new MovementResponse(movement.id(), movement.sessionId(), movement.type(), CashMoney.of(movement.amount()),
                movement.notes(), movement.createdBy(), movement.createdAt());
    }

    private static SessionCloseResponse toResponse(CloseView close) {
        if (close == null) {
            return null;
        }
        return new SessionCloseResponse(close.id(), CashMoney.of(close.declaredAmount()),
                CashMoney.of(close.expectedAmount()), CashMoney.of(close.differenceAmount()), close.differenceReason(),
                close.closedBy(), close.closedAt());
    }

    private static SessionApprovalResponse toResponse(ApprovalView approval) {
        if (approval == null) {
            return null;
        }
        return new SessionApprovalResponse(approval.id(), approval.approvedBy(), approval.note(), approval.approvedAt());
    }

    private static SessionSummaryResponse toSummary(SessionSummary row) {
        return new SessionSummaryResponse(row.id(), row.cashierId(), row.status(), CashMoney.of(row.openingFloat()),
                CashMoney.of(row.declaredAmount()), CashMoney.of(row.expectedAmount()),
                CashMoney.of(row.differenceAmount()), row.openedAt(), row.closedAt(), row.approvedAt());
    }
}
