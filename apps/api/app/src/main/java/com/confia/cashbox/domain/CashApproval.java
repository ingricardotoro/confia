package com.confia.cashbox.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The approval of a close with a difference by someone who is not the cashier. Born once, never edited. */
public record CashApproval(UUID id, UUID sessionId, UUID approvedBy, String note, Instant approvedAt, String requestKey,
        String requestHash) {

    public CashApproval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(approvedBy, "approvedBy");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(approvedAt, "approvedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "CashApproval[id=" + id + ", sessionId=" + sessionId + "]";
    }
}
