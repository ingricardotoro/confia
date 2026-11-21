package com.confia.payments.web;

import com.confia.payments.application.Page;
import com.confia.payments.application.PaymentDetail;
import com.confia.payments.application.PaymentSummary;
import com.confia.payments.application.PostingAccountView;
import java.util.List;

/** The output DTO of every route of the payments. Every amount is rounded by the server to the minor unit. */
final class PaymentMapper {

    private PaymentMapper() {
    }

    static PaymentResponse toResponse(PaymentDetail detail) {
        PaymentDetail.Decision decision = detail.decision();
        return new PaymentResponse(detail.id(), detail.studentId(), detail.studentCode(), detail.guardianId(),
                detail.method(), detail.status(), PaymentMoney.of(detail.amount()), detail.receivedOn(),
                detail.reference(), detail.bankName(), detail.notes(), detail.cashSessionId(),
                detail.ledgerTransactionId(), detail.registeredBy(), detail.registeredAt(),
                decision == null ? null : new DecisionResponse(decision.id(), decision.kind(), decision.decidedBy(),
                        decision.decidedAt(), decision.reason()),
                detail.allocations().stream().map(allocation -> new AllocationResponse(allocation.chargeId(),
                        allocation.conceptCode(), allocation.conceptName(), allocation.periodLabel(), allocation.dueOn(),
                        PaymentMoney.of(allocation.amount()), allocation.allocatedAt())).toList(),
                PaymentMoney.of(detail.allocated()), PaymentMoney.of(detail.unallocated()));
    }

    static PaymentPageResponse toResponse(Page<PaymentSummary> page) {
        return new PaymentPageResponse(page.items().stream().map(row -> new PaymentSummaryResponse(row.id(),
                row.studentId(), row.studentCode(), row.method(), row.status(), PaymentMoney.of(row.amount()),
                row.receivedOn(), row.reference(), row.bankName(), row.registeredBy(), row.registeredAt())).toList(),
                page.total(), page.page(), page.size());
    }

    static PaymentPostingAccountMapResponse toResponse(List<PostingAccountView> views) {
        return new PaymentPostingAccountMapResponse(views.stream().map(view -> new PaymentPostingAccountResponse(
                view.id(), view.purpose(), view.ledgerAccountId(), view.version(), view.updatedAt())).toList());
    }
}
