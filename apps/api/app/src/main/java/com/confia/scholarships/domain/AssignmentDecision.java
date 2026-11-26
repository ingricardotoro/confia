package com.confia.scholarships.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The approval, the rejection or the revocation of a discount assignment: who, when and, for a rejection and a revocation, why. It is
 * born once and never changes: the status of the assignment moves only together with this record. The key and its hash make the
 * request idempotent. The reason may name a person, so {@code toString} does not show it.
 *
 * @param reason only a rejection and a revocation have one
 */
public record AssignmentDecision(UUID id, UUID assignmentId, DecisionKind kind, String reason, UUID decidedBy, Instant decidedAt,
        String requestKey, String requestHash) {

    public AssignmentDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(assignmentId, "assignmentId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if (kind.needsReason() != (reason != null)) {
            throw new InvalidScholarshipsValueException();
        }
    }

    /** Whether this decision answers a request with the same digest. */
    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "AssignmentDecision[id=" + id + ", assignmentId=" + assignmentId + ", kind=" + kind + "]";
    }
}
