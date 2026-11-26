package com.confia.scholarships.domain;

/** What a decision on an award does: approves it, rejects it, or revokes an approved one. */
public enum DecisionKind {
    APPROVED("approved", AwardStatus.APPROVED), REJECTED("rejected", AwardStatus.REJECTED),
    REVOKED("revoked", AwardStatus.REVOKED);

    private final String code;
    private final AwardStatus leadsTo;

    DecisionKind(String code, AwardStatus leadsTo) {
        this.code = code;
        this.leadsTo = leadsTo;
    }

    public String code() {
        return code;
    }

    /** The status an award has after this decision. */
    public AwardStatus leadsTo() {
        return leadsTo;
    }

    /** Whether the decision carries a reason: a rejection and a revocation do, an approval does not. */
    public boolean needsReason() {
        return this != APPROVED;
    }

    public static DecisionKind ofCode(String code) {
        for (DecisionKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unknown decision");
    }
}
