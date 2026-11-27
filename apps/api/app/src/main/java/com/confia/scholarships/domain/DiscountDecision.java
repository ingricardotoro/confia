package com.confia.scholarships.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The approval or the rejection of the creation of a campaign discount or of a proposal on it: who, when and, for a rejection, why. It
 * is born once and never changes: the discount moves only together with this record. It names the version of the discount that waited
 * for it, which is the version the person who decided saw. The key and its hash make the request idempotent. The reason may name a
 * person, so {@code toString} does not show it.
 *
 * @param discountVersion the version of the discount that this decision settles
 * @param reason only a rejection has one
 */
public record DiscountDecision(UUID id, UUID discountId, long discountVersion, DecisionKind kind, String reason, UUID decidedBy,
        Instant decidedAt, String requestKey, String requestHash) {

    public DiscountDecision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(discountId, "discountId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if (kind == DecisionKind.REVOKED || discountVersion < 0 || kind.needsReason() != (reason != null)) {
            throw new InvalidScholarshipsValueException();
        }
    }

    /** Whether this decision answers a request with the same digest. */
    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "DiscountDecision[id=" + id + ", discountId=" + discountId + ", kind=" + kind + "]";
    }
}
