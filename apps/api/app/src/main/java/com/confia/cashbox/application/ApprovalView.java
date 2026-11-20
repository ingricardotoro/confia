package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashApproval;
import java.time.Instant;
import java.util.UUID;

/** The approval of a close as the use cases answer it. The note may name a person. */
public record ApprovalView(UUID id, UUID approvedBy, String note, Instant approvedAt) {

    static ApprovalView of(CashApproval approval) {
        return new ApprovalView(approval.id(), approval.approvedBy(), approval.note(), approval.approvedAt());
    }

    @Override
    public String toString() {
        return "ApprovalView[id=" + id + "]";
    }
}
